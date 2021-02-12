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

@file:JvmName("MessageUtil")

package com.exactpro.th2.ws.client.util

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import java.time.Instant

private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

fun MessageOrBuilder.toPrettyString(): String = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields().print(this)

private fun RawMessage.Builder.toBatch() = run(AnyMessage.newBuilder()::setRawMessage)
    .run(MessageGroup.newBuilder()::addMessages)
    .run(MessageGroupBatch.newBuilder()::addGroups)
    .build()

fun ByteArray.toBatch(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
): MessageGroupBatch = RawMessage.newBuilder().apply {
    this.body = ByteString.copyFrom(this@toBatch)
    this.metadataBuilder {
        this.timestamp = Instant.now().toTimestamp()
        this.idBuilder {
            this.connectionId = connectionId
            this.direction = direction
            this.sequence = sequence
        }
    }
}.toBatch()
