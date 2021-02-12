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
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandler
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CompletionStage

class WebSocketClient(
    private val uri: URI,
    private val handler: IHandler,
    private val onMessage: (message: ByteArray, textual: Boolean, direction: Direction) -> Unit,
    private val onEvent: (cause: Throwable?, message: () -> String) -> Unit
) : IClient, WebSocket.Listener, AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val textFrames = mutableListOf<String>()
    private val binaryFrames = mutableListOf<ByteArray>()
    @Volatile private var running: Boolean = true
    val isRunning: Boolean get() = running
    private var socket = connect()

    override fun sendText(text: String) {
        logger.debug { "Sending text: $text" }
        val preparedText = handler.prepareText(this, text)
        socket.sendText(preparedText, true)
        onMessage(preparedText.toByteArray(), true, SECOND)
    }

    override fun sendBinary(data: ByteArray) {
        logger.debug { "Sending binary: ${data.toBase64()}" }
        val preparedData = handler.prepareBinary(this, data)
        socket.sendBinary(ByteBuffer.wrap(preparedData), true)
        onMessage(preparedData, false, SECOND)
    }

    override fun sendPing(message: ByteArray) {
        logger.debug { "Sending ping: ${message.toBase64()}" }
        socket.sendPing(ByteBuffer.wrap(message))
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
            onMessage(message.toByteArray(), true, FIRST)
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
            onMessage(message, false, FIRST)
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
        this.socket = connect()
        null
    } catch (e: Exception) {
        onError(e) { "Failed to handle onClose event" }
        this.socket = connect()
        null
    }

    override fun onError(socket: WebSocket, error: Throwable) = try {
        onError(error) { "Disconnected from: $uri" }
        handler.onError(error)
        this.socket = connect()
    } catch (e: Exception) {
        onError(e) { "Failed to handle onError event" }
        this.socket = connect()
    }

    override fun close() {
        logger.info { "Closing" }
        running = false

        socket.runCatching {
            if (isOutputClosed) {
                logger.warn { "Trying to close socket abruptly" }
                abort()
            } else {
                logger.info { "Trying to close socket gracefully" }
                sendClose(WebSocket.NORMAL_CLOSURE, "")
            }
        }.onFailure {
            logger.error(it) { "Failed to close socket" }
        }

        logger.info { "Closed" }
    }

    private fun connect(
        reconnectDelay: Long = 1000,
        maxReconnectDelay: Long = 60000
    ): WebSocket {
        if (running) {
            textFrames.clear()
            binaryFrames.clear()

            return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, this)
                .exceptionally {
                    onError(it) { "Failed to connect to: $uri" }
                    Thread.sleep(reconnectDelay)
                    connect((reconnectDelay * 2).coerceAtMost(maxReconnectDelay))
                }
                .get()
        }

        return socket
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

    companion object {
        private fun ByteArray.toBase64() = Base64.getEncoder().encodeToString(this)
        private fun ByteBuffer.toByteArray() = duplicate().run { ByteArray(remaining()).apply(::get) }
    }
}

