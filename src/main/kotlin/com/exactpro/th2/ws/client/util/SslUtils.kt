/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
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
package com.exactpro.th2.ws.client.util

import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.INSECURE_TRUST_MANAGER
import com.exactpro.th2.http.client.util.SingleCertKeyManager
import java.net.http.HttpClient
import javax.net.ssl.SSLContext

fun HttpClient.Builder.sslContext(
    wss: Boolean,
    validateCertificates: Boolean,
    clientCertificate: Certificate?
) = apply {
    if (!wss) {
        return@apply
    }

    val keyManagers = clientCertificate?.run { arrayOf(SingleCertKeyManager(this)) }
    val trustManagers = if (validateCertificates) null else arrayOf(INSECURE_TRUST_MANAGER)

    sslContext(SSLContext.getInstance("TLS").apply {
        init(keyManagers, trustManagers, null)
    })
}