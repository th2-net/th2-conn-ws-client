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

package com.exactpro.th2.ws.client.api.impl

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IClientSettings
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.util.sslContext
import mu.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WebSocketClient(
    private val uri: URI,
    private val handler: IHandler,
    private val onMessage: (message: ByteArray, textual: Boolean, direction: Direction) -> Unit,
    private val onEvent: (cause: Throwable?, message: () -> String) -> Unit,
    private val validateCertificates: Boolean = true,
    private val clientCertificate: Certificate? = null
) : IClient, WebSocket.Listener {
    private val logger = KotlinLogging.logger {}
    private val textFrames = mutableListOf<String>()
    private val binaryFrames = mutableListOf<ByteArray>()
    private val lock = ReentrantLock()
    private val isWSS = uri.scheme.equals("wss", true)

    @Volatile private lateinit var socket: WebSocket

    @Volatile private var connectionFuture: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    @Volatile var isRunning: Boolean = false
        private set

    private fun awaitSocket(): WebSocket {
        if (!isRunning) start()

        while (!::socket.isInitialized || socket.isOutputClosed) {
            when {
                isRunning -> Thread.sleep(1)
                else -> return awaitSocket()
            }
        }

        return socket
    }

    override fun sendText(text: String) {
        logger.debug { "Sending text: $text" }
        val preparedText = handler.prepareText(this, text)
        awaitSocket().sendText(preparedText, true)
        onMessage(preparedText.toByteArray(), true, SECOND)
    }

    override fun sendBinary(data: ByteArray) {
        logger.debug { "Sending binary: ${data.toBase64()}" }
        val preparedData = handler.prepareBinary(this, data)
        awaitSocket().sendBinary(ByteBuffer.wrap(preparedData), true)
        onMessage(preparedData, false, SECOND)
    }

    override fun sendPing(message: ByteArray) {
        logger.debug { "Sending ping: ${message.toBase64()}" }
        awaitSocket().sendPing(ByteBuffer.wrap(message))
    }

    override fun onOpen(socket: WebSocket) {
        onInfo { "Connected to: $uri" }
        this.socket = socket
        handler.onOpen(this)
        socket.request(1)
    }

    override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
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

        return onComplete(socket)
    }

    override fun onBinary(socket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
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

        return onComplete(socket)
    }

    override fun onPing(socket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        val data = message.toByteArray()
        logger.debug { "onPing(message: ${data.toBase64()})" }
        handler.onPing(this, data)
        return onComplete(socket)
    }

    override fun onPong(socket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        val data = message.toByteArray()
        logger.debug { "onPong(message: ${data.toBase64()})" }
        handler.onPong(this, data)
        return onComplete(socket)
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
                runCatching(::connect).onFailure {
                    isRunning = false
                    throw IllegalStateException("Failed to connect", it)
                }
                onInfo { "Started client" }
            }
        }
    }

    fun stop() {
        if (!isRunning) {
            onInfo { "Client is already stopped or is being stopped" }
            return
        }

        isRunning = false
        connectionFuture.cancel(true)

        lock.withLock {
            onInfo { "Stopping client" }

            socket.runCatching {
                if (isOutputClosed) {
                    logger.warn { "Trying to close socket abruptly" }
                    abort()
                } else {
                    logger.info { "Trying to close socket gracefully" }
                    handler.runCatching(IHandler::preClose).onFailure { onError(it) { "Failed to handle preClose event" } }
                    sendClose(WebSocket.NORMAL_CLOSURE, "")
                }
            }.onFailure {
                logger.error(it) { "Failed to close socket" }
            }

            onInfo { "Stopped client" }
        }
    }

    private fun connect() = lock.withLock {
        var delay = 0L
        while (isRunning) {
            val settings = WebSocketClientSettings(uri)
            try {
                textFrames.clear()
                binaryFrames.clear()

                val webSocketFuture = HttpClient.newBuilder()
                    .sslContext(isWSS, validateCertificates, clientCertificate)
                    .build()
                    .newWebSocketBuilder()
                    // avoid deadlock when we try to reconnect and close the client
                    .connectTimeout(Duration.ofSeconds(60))
                    .also { settings.builder = it; handler.preOpen(settings) }
                    .buildAsync(settings.uri, this)

                connectionFuture = webSocketFuture

                webSocketFuture.get()

                break
            } catch (e: Exception) {
                delay += 5000L
                onError(e) { "Failed to connect to: ${settings.uri}. Retrying in $delay ms" }
                Thread.sleep(delay)
            }
        }
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

    private class WebSocketClientSettings(baseUri: URI) : IClientSettings {
        private var uriBuilder: URIBuilder = URIBuilder(baseUri)

        lateinit var builder: WebSocket.Builder
        val uri: URI get() = uriBuilder.build()

        override fun addHeader(name: String, value: String) {
            builder.header(name, value)
        }

        override fun subprotocols(mostPreferred: String, vararg lesserPreferred: String) {
            builder.subprotocols(mostPreferred, *lesserPreferred)
        }

        override fun addQueryParam(name: String, value: String) {
            uriBuilder.addQueryParam(name, value)
        }
    }

    private class URIBuilder(uri: URI) {
        private val builder: StringBuilder = StringBuilder(uri.toString())
        private var hasQuery = uri.query != null

        fun addQueryParam(name: String, value: String) {
            if (hasQuery) builder.append('&') else builder.append('?')
            builder.append("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}") // add encoding
            hasQuery = true
        }
        fun build(): URI = URI(builder.toString())
    }

    companion object {
        private fun ByteArray.toBase64() = Base64.getEncoder().encodeToString(this)
        private fun ByteBuffer.toByteArray() = duplicate().run { ByteArray(remaining()).apply(::get) }
    }
}

