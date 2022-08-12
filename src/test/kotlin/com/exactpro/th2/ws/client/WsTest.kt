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