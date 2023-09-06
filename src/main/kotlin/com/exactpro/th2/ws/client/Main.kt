/*
 * Copyright 2021-2023 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.grpc.router.GrpcRouter
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.toByteArray
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.common.utils.message.RAW_GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.transport.MessageBatcher.Companion.GROUP_SELECTOR
import com.exactpro.th2.common.utils.shutdownGracefully
import com.exactpro.th2.ws.client.Settings.FrameType.TEXT
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.api.IHandlerSettings
import com.exactpro.th2.ws.client.api.IHandlerSettingsTypeProvider
import com.exactpro.th2.ws.client.api.impl.DefaultHandler
import com.exactpro.th2.ws.client.api.impl.DefaultHandlerSettingsTypeProvider
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import com.exactpro.th2.ws.client.util.toPrettyString
import com.exactpro.th2.ws.client.util.toProto
import com.exactpro.th2.ws.client.util.toTransport
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
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
import com.exactpro.th2.common.utils.message.RawMessageBatcher as ProtoMessageBatcher
import com.exactpro.th2.common.utils.message.transport.MessageBatcher as TransportMessageBatcher

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
        .addModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, enabled = true)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
        .addModule(SimpleModule().addAbstractTypeMapping(IHandlerSettings::class.java, handlerSettingType))
        .build()

    run(
        factory.boxConfiguration.bookName,
        factory.rootEventId,
        factory.getCustomConfiguration(Settings::class.java, mapper),
        factory.eventBatchRouter,
        factory.messageRouterMessageGroupBatch,
        factory.transportGroupBatchRouter,
        factory.grpcRouter,
        handler
    ) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

private const val RAW_QUEUE_ATTRIBUTE = "raw"

fun run(
    book: String,
    rootEventId: EventID,
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    protoMessageRouter: MessageRouter<MessageGroupBatch>,
    transportMessageRouter: MessageRouter<GroupBatch>,
    grpcRouter: GrpcRouter,
    handler: IHandler,
    registerResource: (name: String, destructor: () -> Unit) -> Unit
) {
    settings.handlerSettings.runCatching(handler::init).onFailure {
        LOGGER.error(it) { "Failed to init request handler" }
        eventRouter.storeEvent(rootEventId, "Failed to init request handler", "Error", it)
        throw it
    }

    val incomingSequence = createSequence()
    val outgoingSequence = createSequence()

    val executor = Executors.newScheduledThreadPool(1).also {
        registerResource("executor", it::shutdownGracefully)
    }

    val onMessage: (message: ByteArray, textual: Boolean, direction: Direction) -> Unit = if (settings.useTransport) {
        val messageBatcher = TransportMessageBatcher(
            settings.maxBatchSize,
            settings.maxFlushTime,
            book,
            GROUP_SELECTOR,
            executor
        ) { batch ->
            transportMessageRouter.send(batch)
        }.apply {
            registerResource("message-batcher", ::close)
        }

        fun(message: ByteArray, _: Boolean, direction: Direction) {
            val sequence = if (direction == Direction.FIRST) incomingSequence else outgoingSequence
            messageBatcher.onMessage(message.toTransport(settings.sessionAlias, direction, sequence()), settings.sessionGroup)
        }
    } else {
        val messageBatcher = ProtoMessageBatcher(
            settings.maxBatchSize,
            settings.maxFlushTime,
            RAW_GROUP_SELECTOR,
            executor
        ) { batch ->
            protoMessageRouter.send(batch, QueueAttribute.RAW.value)
        }.apply {
            registerResource("proto-message-batcher", ::close)
        }

        val connectionId = ConnectionID.newBuilder().setSessionAlias(settings.sessionAlias).build()

        fun(message: ByteArray, _: Boolean, direction: Direction) {
            val sequence = if (direction == Direction.FIRST) incomingSequence else outgoingSequence
            messageBatcher.onMessage(message.toProto(connectionId, direction, sequence()))
        }
    }

    val onEvent: (cause: Throwable?, message: () -> String) -> Unit = { cause: Throwable?, message: () -> String ->
        val type = if (cause != null) "Error" else "Info"
        eventRouter.storeEvent(rootEventId, message(), type, cause)
    }

    val client = WebSocketClient(
        URI(settings.uri),
        handler,
        onMessage,
        onEvent
    ).apply { registerResource("client", ::stop) }

    val controller = ClientController(client).apply { registerResource("controller", ::close) }

    val protoListener = MessageListener<MessageGroupBatch> { _, groupBatch ->
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

    val proto = runCatching {
        checkNotNull(protoMessageRouter.subscribe(protoListener, INPUT_QUEUE_ATTRIBUTE, RAW_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        registerResource("proto-raw-monitor", monitor::unsubscribe)
    }.onFailure {
        LOGGER.warn(it) { "Failed to subscribe to input protobuf queue" }
    }

    val transportListener = MessageListener<GroupBatch> { _, groupBatch ->
        if (!controller.isRunning) { // should we reschedule stop if service is already running?
            controller.start(settings.autoStopAfter)
        }

        groupBatch.groups.forEach { group ->
            group.runCatching {
                require(messages.size == 1) { "Message group contains more than 1 message" }
                val message = messages.single()
                require(message is RawMessage) { "Message in the group is not a raw message" }
                settings.frameType.send(client, message.body.toByteArray())
            }.recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: $group" }
                eventRouter.storeEvent(rootEventId, "Failed to handle message group: $group", "Error", it)
            }
        }
    }

    val transport = runCatching {
        checkNotNull(transportMessageRouter.subscribe(transportListener, INPUT_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        registerResource("transport-raw-monitor", monitor::unsubscribe)
    }.onFailure {
        LOGGER.warn(it) { "Failed to subscribe to input transport queue" }
    }

    if (proto.isFailure && transport.isFailure) {
        error("Subscribe pin should be declared at least one of protobuf or transport protocols")
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
    val sessionGroup: String,
    val handlerSettings: IHandlerSettings? = null,
    val grpcStartControl: Boolean = false,
    val autoStart: Boolean = true,
    val autoStopAfter: Int = 0,
    val maxBatchSize: Int = 100,
    val maxFlushTime: Long  = 1000,
    val useTransport: Boolean = true
) {

    enum class FrameType {
        TEXT {
            override fun send(client: IClient, data: ByteArray) = client.sendText(data.toString(UTF_8))
        },
        @Suppress("unused")
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
