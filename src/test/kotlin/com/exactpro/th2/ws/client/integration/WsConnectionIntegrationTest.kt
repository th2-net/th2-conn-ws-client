/*
 * Copyright 2024 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.ws.client.integration

import com.exactpro.th2.common.schema.box.configuration.BoxConfiguration
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.toByteArray
import com.exactpro.th2.common.utils.message.transport.toBatch
import com.exactpro.th2.common.utils.message.transport.toGroup
import com.exactpro.th2.conn.grpc.ConnService
import com.exactpro.th2.test.annotations.CustomConfigProvider
import com.exactpro.th2.test.annotations.Th2AppFactory
import com.exactpro.th2.test.annotations.Th2IntegrationTest
import com.exactpro.th2.test.annotations.Th2TestFactory
import com.exactpro.th2.test.extension.CleanupExtension
import com.exactpro.th2.test.queue.CollectorMessageListener
import com.exactpro.th2.test.spec.CustomConfigSpec
import com.exactpro.th2.test.spec.GrpcSpec
import com.exactpro.th2.test.spec.RabbitMqSpec
import com.exactpro.th2.test.spec.pin
import com.exactpro.th2.test.spec.pins
import com.exactpro.th2.test.spec.publishers
import com.exactpro.th2.test.spec.server
import com.exactpro.th2.test.spec.subscribers
import com.exactpro.th2.ws.client.runApplication
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.withLock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingDeque
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail


@Tag("integration")
@Th2IntegrationTest
internal class WsConnectionIntegrationTest {
    private lateinit var webServer: MockWebServer

    @BeforeAll
    fun beforeAll() {
        webServer = MockWebServer()
        webServer.start()
    }

    @AfterAll
    fun afterAll() {
        webServer.shutdown()
    }

    val grpc = GrpcSpec.create()
        .server<ConnService>()

    val mq = RabbitMqSpec.create()
        .pins {
            subscribers {
                pin("in") {
                    attributes("transport-group", "send")
                }
            }
            publishers {
                pin("out") {
                    attributes("transport-group")
                }
            }
        }

    @Test
    @CustomConfigProvider("config")
    fun `test connects to webserver`(
        @Th2AppFactory factory: CommonFactory,
        @Th2TestFactory test: CommonFactory,
        resources: CleanupExtension.Registry,
    ) {
        val serverListener = WebSocketRecorder()
        webServer.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val msgListener = CollectorMessageListener.createUnbound<GroupBatch>()
        test.transportGroupBatchRouter.subscribe(msgListener, "out")
        runApplication(factory) { name, action -> resources.add(name, action) }

        val ws = serverListener.assertConnected(2000)
        ws.send("Hello")

        val msg = assertNotNull(msgListener.poll(Duration.ofMillis(1000)), "message was not produced")
        Assertions.assertNotNull(msg.groups.singleOrNull()) { "no groups in bath $msg" }
        Assertions.assertNotNull(msg.groups.single().messages.singleOrNull()) {
            "no messages in group ${msg.groups}"
        }
        val rawMsg = msg.groups.single().messages.single()
        assertIs<RawMessage>(rawMsg, "not a raw message")
        assertEquals(
            "Hello",
            rawMsg.body.toByteArray().toString(Charsets.UTF_8),
            "unexpected message",
        )
        assertEquals(
            Direction.INCOMING,
            rawMsg.id.direction,
            "unexpected direction",
        )
        ws.close(1000, "close")
    }

    @Test
    @CustomConfigProvider("config")
    fun `sends message to webserver`(
        @Th2AppFactory factory: CommonFactory,
        @Th2TestFactory test: CommonFactory,
        resources: CleanupExtension.Registry,
    ) {
        val serverListener = WebSocketRecorder()
        webServer.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val msgListener = CollectorMessageListener.createUnbound<GroupBatch>()
        test.transportGroupBatchRouter.subscribe(msgListener, "out")
        runApplication(factory) { name, action -> resources.add(name, action) }

        test.transportGroupBatchRouter.send(
            RawMessage.builder()
                .setBody("Hello".toByteArray(Charsets.UTF_8))
                .setId(MessageId.DEFAULT)
                .build()
                .toGroup()
                .toBatch(BoxConfiguration.DEFAULT_BOOK_NAME, "sessionGroup"),
            "in",
        )

        val ws = serverListener.assertConnected(2000)
        val receivedByServer = serverListener.assertReceived()
        assertEquals("Hello", receivedByServer, "unexpected message received by server")
        val msg = assertNotNull(msgListener.poll(Duration.ofMillis(1000)), "message was not produced")
        Assertions.assertNotNull(msg.groups.singleOrNull()) { "no groups in bath $msg" }
        Assertions.assertNotNull(msg.groups.single().messages.singleOrNull()) {
            "no messages in group ${msg.groups}"
        }
        val rawMsg = msg.groups.single().messages.single()
        assertIs<RawMessage>(rawMsg, "not a raw message")
        assertEquals(
            "Hello",
            rawMsg.body.toByteArray().toString(Charsets.UTF_8),
            "unexpected message",
        )
        assertEquals(
            Direction.OUTGOING,
            rawMsg.id.direction,
            "unexpected direction",
        )
        ws.close(1000, "close")
    }

    fun config(): CustomConfigSpec =
        CustomConfigSpec.fromString(
            """
            {
                "uri": "ws://localhost:${webServer.port}",
                "frameType": "TEXT",
                "sessionAlias": "api_session",
                "sessionGroup": "sessionGroup",
                "autoStart": true,
                "handlerSettings": {
                    "pingInterval": 10000
                },
                "maxFlushTime": 100
            }
            """.trimIndent()
        )

    private class WebSocketRecorder : WebSocketListener() {
        private val messages: BlockingQueue<String> = LinkedBlockingQueue()
        private lateinit var webSocket: WebSocket
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private var closed: Boolean = true

        override fun onOpen(webSocket: WebSocket, response: Response) {
            lock.withLock {
                closed = false
                this.webSocket = webSocket
                condition.signalAll()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lock.withLock {
                closed = true
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.put(text)
        }

        fun assertConnected(millis: Long = 1000): WebSocket {
            lock.withLock {
                if (::webSocket.isInitialized) {
                    assertFalse(closed, "connection is closed")
                    return webSocket
                }
                if (condition.await(millis, TimeUnit.MILLISECONDS)) {
                    assertFalse(closed, "connection is closed")
                    return webSocket
                }
                fail("websocket is not initialized")
            }
        }

        fun assertReceived(millis: Long = 1000): String {
            return assertNotNull(messages.poll(millis, TimeUnit.MILLISECONDS), "no message received")
        }
    }
}