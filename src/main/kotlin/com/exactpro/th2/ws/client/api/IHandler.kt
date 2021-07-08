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

package com.exactpro.th2.ws.client.api

import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
interface IHandler : AutoCloseable {
    fun init(settings: IHandlerSettings?) {}
    fun onOpen(client: IClient) {}
    fun onText(client: IClient, text: String) {}
    fun onBinary(client: IClient, data: ByteArray) {}
    fun onPing(client: IClient, data: ByteArray) {}
    fun onPong(client: IClient, data: ByteArray) {}
    fun onClose(statusCode: Int, reason: String) {}
    fun onError(error: Throwable) {}
    fun preClose() {}
    fun prepareText(client: IClient, text: String): String = text
    fun prepareBinary(client: IClient, data: ByteArray): ByteArray = data
    override fun close() {}
}