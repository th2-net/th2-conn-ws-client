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

package com.exactpro.th2.ws.client.api.impl

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IClientSettings
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WebSocketClient(
    private val uri: URI,
    private val handler: IHandler,
    private val onMessage: (message: ByteArray, textual: Boolean, direction: Direction, eventId: EventID?) -> Unit,
    private val onEvent: (cause: Throwable?, message: () -> String) -> Unit
) : IClient, WebSocket.Listener {
    private val logger = KotlinLogging.logger {}
    private val textFrames = mutableListOf<String>()
    private val binaryFrames = mutableListOf<ByteArray>()
    private val lock = ReentrantLock()
    private lateinit var socket: WebSocket

    @Volatile var isRunning: Boolean = false
        private set

    private fun awaitSocket(): WebSocket {
        when {
            !isRunning -> start()
            !::socket.isInitialized || socket.isOutputClosed -> connect()
        }

        return socket
    }

    override fun sendText(text: String, eventId: EventID?) = lock.withLock {
        logger.debug { "Sending text: $text" }
        val preparedText = handler.prepareText(this, text)
        awaitSocket().sendText(preparedText, true)
        onMessage(preparedText.toByteArray(), true, SECOND, eventId)
    }

    override fun sendBinary(data: ByteArray, eventId: EventID?) = lock.withLock {
        logger.debug { "Sending binary: ${data.toBase64()}" }
        val preparedData = handler.prepareBinary(this, data)
        awaitSocket().sendBinary(ByteBuffer.wrap(preparedData), true)
        onMessage(preparedData, false, SECOND, eventId)
    }

    override fun sendPing(message: ByteArray): Unit = lock.withLock {
        logger.debug { "Sending ping: ${message.toBase64()}" }
        awaitSocket().sendPing(ByteBuffer.wrap(message))
    }

    override fun onOpen(socket: WebSocket) = try {
        onInfo { "Connected to: $uri" }
        handler.onOpen(this)
        socket.request(1)
    } catch (e: Exception) {
        onError(e) { "Failed to handle onOpen event" }
        socket.request(1)
    }

    override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? = try {
        val frame = data.toString()
        logger.debug { "onText(data: $frame, last: $last)" }

        if (!last || textFrames.isNotEmpty()) {
            textFrames += frame
        }

        if (last) {
            val message = if (textFrames.isEmpty()) frame else textFrames.joinToString("")
            handler.onText(this, message)
            onMessage(message.toByteArray(), true, FIRST, null)
            textFrames.clear()
        }

        onComplete(socket)
    } catch (e: Exception) {
        onError(e) { "Failed to handle onText event" }
        onComplete(socket)
    }

    override fun onBinary(socket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? = try {
        val frame = data.toByteArray()
        logger.debug { "onBinary(data: ${frame.toBase64()}, last: $last)" }

        if (!last || binaryFrames.isNotEmpty()) {
            binaryFrames += frame
        }

        if (last) {
            val message = if (binaryFrames.isEmpty()) frame else binaryFrames.reduce(ByteArray::plus)
            handler.onBinary(this, message)
            onMessage(message, true, FIRST, null)
            binaryFrames.clear()
        }

        onComplete(socket)
    } catch (e: Exception) {
        onError(e) { "Failed to handle onBinary event" }
        onComplete(socket)
    }

    override fun onPing(socket: WebSocket, message: ByteBuffer): CompletionStage<*>? = try {
        val data = message.toByteArray()
        logger.debug { "onPing(message: ${data.toBase64()})" }
        handler.onPing(this, data)
        onComplete(socket)
    } catch (e: Exception) {
        onError(e) { "Failed to handle onPing event" }
        onComplete(socket)
    }

    override fun onPong(socket: WebSocket, message: ByteBuffer): CompletionStage<*>? = try {
        val data = message.toByteArray()
        logger.debug { "onPong(message: ${data.toBase64()})" }
        handler.onPong(this, data)
        onComplete(socket)
    } catch (e: Exception) {
        onError(e) { "Failed to handle onPong event" }
        onComplete(socket)
    }

    override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? = try {
        onInfo { "Disconnected from: $uri - statusCode: $statusCode, reason: $reason" }
        handler.onClose(statusCode, reason)
        connect()
        null
    } catch (e: Exception) {
        onError(e) { "Failed to handle onClose event" }
        connect()
        null
    }

    override fun onError(socket: WebSocket, error: Throwable) = try {
        onError(error) { "Disconnected from: $uri" }
        handler.onError(error)
        connect()
    } catch (e: Exception) {
        onError(e) { "Failed to handle onError event" }
        connect()
    }

    fun start() = lock.withLock {
        when {
            isRunning -> onInfo { "Client is already running" }
            else -> {
                onInfo { "Starting client" }
                isRunning = true
                connect()
                onInfo { "Started client" }
            }
        }
    }

    fun stop() = lock.withLock {
        if (!isRunning || !::socket.isInitialized) {
            onInfo { "Client is already stopped" }
            return
        }

        onInfo { "Stopping client" }

        socket.runCatching {
            isRunning = false

            if (isOutputClosed) {
                logger.warn { "Trying to close socket abruptly" }
                abort()
            } else {
                logger.info { "Trying to close socket gracefully" }

                handler.runCatching(IHandler::preClose).onFailure {
                    onError(it) { "Failed to handle preClose event" }
                }

                sendClose(WebSocket.NORMAL_CLOSURE, "")
            }
        }.onFailure {
            logger.error(it) { "Failed to close socket" }
        }

        onInfo { "Stopped client" }
    }

    private fun connect() = lock.withLock {
        if (!isRunning) return
        this.socket = createSocket()
    }

    private fun createSocket(
        reconnectDelay: Long = 1000,
        maxReconnectDelay: Long = 60000
    ): WebSocket {
        textFrames.clear()
        binaryFrames.clear()

        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .also {
                handler.preOpen(WebSocketClientSettings(it))
            }
            .buildAsync(uri, this)
            .exceptionally {
                onError(it) { "Failed to connect to: $uri" }
                Thread.sleep(reconnectDelay)
                createSocket((reconnectDelay * 2).coerceAtMost(maxReconnectDelay))
            }
            .get()
    }

    private fun onInfo(message: () -> String) {
        logger.info(message)
        onEvent(null, message)
    }

    private fun onError(cause: Throwable, message: () -> String) {
        logger.error(cause, message)
        onEvent(cause, message)
    }

    private fun onComplete(socket: WebSocket): CompletionStage<*>? {
        socket.request(1)
        return null
    }

    private class WebSocketClientSettings(private val builder: WebSocket.Builder) : IClientSettings {
        override fun addHeader(name: String, value: String) {
            builder.header(name, value)
        }
    }

    companion object {
        private fun ByteArray.toBase64() = Base64.getEncoder().encodeToString(this)
        private fun ByteBuffer.toByteArray() = duplicate().run { ByteArray(remaining()).apply(::get) }
    }
}

