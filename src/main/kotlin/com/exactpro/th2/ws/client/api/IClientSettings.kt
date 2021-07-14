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

/**
 * Provides the ability to configure the [IClient] implementation
 */
interface IClientSettings {
    /**
     * Adds HTTP header to the WebSocket client handshake request
     */
    fun addHeader(name: String, value: String)

    fun addHeaders(name: String, values: Collection<String>) {
        values.forEach { addHeader(name, it) }
    }
}
