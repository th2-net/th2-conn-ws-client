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

package com.exactpro.th2.ws.client

import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import java.net.URI

class WsTest {
    private val LOGGER = KotlinLogging.logger { }

    //@Test
    fun `profiler test`() {
        val handler = object: IHandler {
            override fun onOpen(client: IClient) {
                LOGGER.info { "Client opened" }
            }

            override fun onClose(statusCode: Int, reason: String) {
                LOGGER.info { "Client closed" }
            }

            override fun onText(client: IClient, text: String) {
                LOGGER.info { "Was received [$text]" }
            }

            override fun prepareText(client: IClient, text: String): String {
                LOGGER.info { "Sending [$text]" }
                return super.prepareText(client, text)
            }
        }
        val client = WebSocketClient(
            URI.create("wss://ws.postman-echo.com/raw"),
            handler,
            onMessage = { _, _, _ ->
                
            },
            onEvent = { _, _ ->

            }
        )

        client.start()
        while (true) {
            Thread.sleep(1000)
            client.sendText("test")
            LOGGER.info { "Client still running" }
        }
    }
}