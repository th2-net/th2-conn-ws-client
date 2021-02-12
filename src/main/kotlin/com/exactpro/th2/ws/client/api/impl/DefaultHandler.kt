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

import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.api.IHandlerSettings
import mu.KotlinLogging
import java.util.Timer
import kotlin.concurrent.timer

class DefaultHandler : IHandler {
    private val logger = KotlinLogging.logger {}
    private lateinit var settings: DefaultHandlerSettings
    private lateinit var timer: Timer

    override fun init(settings: IHandlerSettings?) {
        this.settings = requireNotNull(settings as? DefaultHandlerSettings) {
            "settings is not an instance of ${DefaultHandlerSettings::class.simpleName}"
        }
    }

    override fun onOpen(client: IClient) {
        createTimer(client)
    }

    override fun onPing(client: IClient, data: ByteArray) {
        cancelTimer()
        createTimer(client)
    }

    private fun createTimer(client: IClient) {
        this.timer = timer(initialDelay = settings.pingInterval, period = settings.pingInterval) {
            EMPTY_MESSAGE.runCatching(client::sendPing).onFailure {
                logger.error(it) { "Failed to send ping" }
            }
        }
    }

    private fun cancelTimer() {
        timer.runCatching(Timer::cancel).onFailure {
            logger.error(it) { "Failed to cancel existing ping timer" }
        }
    }

    override fun close() {
        cancelTimer()
    }

    companion object {
        private val EMPTY_MESSAGE = byteArrayOf()
    }
}