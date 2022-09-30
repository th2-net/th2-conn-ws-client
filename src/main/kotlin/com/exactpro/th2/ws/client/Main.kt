/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Main")

package com.exactpro.th2.ws.client

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.grpc.router.GrpcRouter
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.direction
import com.exactpro.th2.ws.client.Settings.FrameType.TEXT
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.api.IHandlerSettings
import com.exactpro.th2.ws.client.api.IHandlerSettingsTypeProvider
import com.exactpro.th2.ws.client.api.impl.DefaultHandler
import com.exactpro.th2.ws.client.api.impl.DefaultHandlerSettingsTypeProvider
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import com.exactpro.th2.ws.client.util.toPrettyString
import com.exactpro.th2.ws.client.util.toRawMessage
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import org.apache.commons.lang3.exception.ExceptionUtils
import java.net.URI
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8

private val LOGGER = KotlinLogging.logger { }
private const val INPUT_QUEUE_ATTRIBUTE = "send"

fun main(args: Array<String>) = try {
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val handler = load<IHandler>(DefaultHandler::class.java).apply { resources += "handler" to ::close }
    val handlerSettingType = load<IHandlerSettingsTypeProvider>(DefaultHandlerSettingsTypeProvider::class.java).type

    val factory = args.runCatching(CommonFactory::createFromArguments).getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule(nullIsSameAsDefault = true))
        .addModule(SimpleModule().addAbstractTypeMapping(IHandlerSettings::class.java, handlerSettingType))
        .build()

    val settings = factory.getCustomConfiguration(Settings::class.java, mapper)
    val eventRouter = factory.eventBatchRouter
    val messageRouter = factory.messageRouterMessageGroupBatch
    val grpcRouter = factory.grpcRouter

    run(settings, eventRouter, messageRouter, grpcRouter, handler) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

fun run(
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    messageRouter: MessageRouter<MessageGroupBatch>,
    grpcRouter: GrpcRouter,
    handler: IHandler,
    registerResource: (name: String, destructor: () -> Unit) -> Unit
) {
    val connectionId = ConnectionID.newBuilder().setSessionAlias(settings.sessionAlias).build()

    val rootEventId = eventRouter.storeEvent(Event.start().apply {
        name("WS client '${settings.sessionAlias}' ${Instant.now()}")
        type("Microservice")
    }).id

    settings.handlerSettings.runCatching(handler::init).onFailure {
        LOGGER.error(it) { "Failed to init request handler" }
        eventRouter.storeEvent(rootEventId, "Failed to init request handler", "Error", it)
        throw it
    }

    val incomingSequence = createSequence()
    val outgoingSequence = createSequence()

    val scheduledExecutorService = Executors.newScheduledThreadPool(1).also {
        registerResource("Batcher scheduled executor", it::shutdownNow)
    }

    val batcher = RawMessageBatcher(settings.maxBatchSize, settings.maxFlushTime, {
        it.metadataOrBuilder.id.direction
    }, scheduledExecutorService, {
        when (it.groupsList.first().direction) {
            Direction.FIRST -> messageRouter.send(it, QueueAttribute.FIRST.value)
            Direction.SECOND -> messageRouter.send(it, QueueAttribute.SECOND.value)
            else -> error("Unrecognized direction")
        }
    }) {
        LOGGER.error(it) { "Can't send message group batch due inner error" }
    }.also {
        registerResource("Raw message batcher", it::close)
    }

    val onMessage = { message: ByteArray, _: Boolean, direction: Direction ->
        batcher.onMessage(message.toRawMessage(
            connectionId,
            direction,
            (if (direction == Direction.FIRST) incomingSequence else outgoingSequence)()
        ))
    }

    val eventBatcher = EventBatcher(settings.maxBatchSize, settings.maxFlushTime, scheduledExecutorService, eventRouter::send).also {
        registerResource("Event batcher", it::close)
    }

    val onEvent = { cause: Throwable?, message: () -> String ->
        eventBatcher.storeEvent(message(), cause, rootEventId)
    }

    val client = WebSocketClient(
        URI(settings.uri),
        handler,
        onMessage,
        onEvent
    ).apply { registerResource("client", ::stop) }

    val controller = ClientController(client).apply { registerResource("controller", ::close) }

    val listener = MessageListener<MessageGroupBatch> { _, groupBatch ->
        if (!controller.isRunning) { // should we reschedule stop if service is already running?
            controller.start(settings.autoStopAfter)
        }

        groupBatch.groupsList.forEach { group ->
            group.runCatching {
                require(messagesCount == 1) { "Message group contains more than 1 message" }
                val message = messagesList[0]
                require(message.hasRawMessage()) { "Message in the group is not a raw message" }
                settings.frameType.send(client, message.rawMessage.body.toByteArray())
            }.recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                eventRouter.storeEvent(rootEventId, "Failed to handle message group: ${group.toPrettyString()}", "Error", it)
            }
        }
    }

    runCatching {
        checkNotNull(messageRouter.subscribe(listener, INPUT_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        registerResource("raw-monitor", monitor::unsubscribe)
    }.onFailure {
        throw IllegalStateException("Failed to subscribe to input queue", it)
    }

    if (settings.autoStart) client.start()

    if (settings.grpcStartControl) grpcRouter.startServer(ControlService(controller))

    LOGGER.info { "Successfully started" }

    ReentrantLock().run {
        val condition = newCondition()
        registerResource("await-shutdown") { withLock(condition::signalAll) }
        withLock(condition::await)
    }

    LOGGER.info { "Finished running" }
}

data class Settings(
    val uri: String,
    val frameType: FrameType = TEXT,
    val sessionAlias: String,
    val handlerSettings: IHandlerSettings? = null,
    val grpcStartControl: Boolean = false,
    val autoStart: Boolean = true,
    val autoStopAfter: Int = 0,
    val maxBatchSize: Int = 100,
    val maxFlushTime: Long = 1000
) {
    enum class FrameType {
        TEXT {
            override fun send(client: IClient, data: ByteArray) = client.sendText(data.toString(UTF_8))
        },
        BINARY {
            override fun send(client: IClient, data: ByteArray) = client.sendBinary(data)
        };

        abstract fun send(client: IClient, data: ByteArray)
    }
}

private inline fun <reified T> load(defaultImpl: Class<out T>): T {
    val instances = ServiceLoader.load(T::class.java).toList()

    return when (instances.size) {
        0 -> error("No instances of ${T::class.simpleName}")
        1 -> instances.first()
        2 -> instances.first { !defaultImpl.isInstance(it) }
        else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
    }
}

private fun createSequence(): () -> Long = Instant.now().run {
    AtomicLong(epochSecond * SECONDS.toNanos(1) + nano)
}::incrementAndGet

fun EventBatcher.storeEvent(name: String, cause: Throwable?, parentEventId: String) {
    val event = createEvent(name, cause)
    onEvent(event.toProtoEvent(parentEventId))
}

fun createEvent(
    name: String,
    cause: Throwable? = null
): Event = Event.start().apply {
    endTimestamp()
    name(name)
    type(if (cause != null) "Error" else "Info")
    status(if (cause != null) Event.Status.FAILED else Event.Status.PASSED)

    generateSequence(cause, Throwable::cause).forEach { error ->
        bodyData(EventUtils.createMessageBean(ExceptionUtils.getMessage(error)))
    }
}