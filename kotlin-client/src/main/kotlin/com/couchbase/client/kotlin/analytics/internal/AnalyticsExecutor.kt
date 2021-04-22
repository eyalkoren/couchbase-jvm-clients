/*
 * Copyright 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.kotlin.analytics.internal

import com.couchbase.client.core.Core
import com.couchbase.client.core.cnc.TracingIdentifiers
import com.couchbase.client.core.msg.analytics.AnalyticsRequest
import com.couchbase.client.core.util.Golang
import com.couchbase.client.kotlin.CommonOptions
import com.couchbase.client.kotlin.Scope
import com.couchbase.client.kotlin.analytics.AnalyticsFlowItem
import com.couchbase.client.kotlin.analytics.AnalyticsMetadata
import com.couchbase.client.kotlin.analytics.AnalyticsParameters
import com.couchbase.client.kotlin.analytics.AnalyticsPriority
import com.couchbase.client.kotlin.analytics.AnalyticsRow
import com.couchbase.client.kotlin.analytics.AnalyticsScanConsistency
import com.couchbase.client.kotlin.codec.JsonSerializer
import com.couchbase.client.kotlin.codec.typeRef
import com.couchbase.client.kotlin.env.env
import com.couchbase.client.kotlin.logicallyComplete
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow

internal class AnalyticsExecutor(
    private val core: Core,
    private val scope: Scope? = null,
) {
    private val bucketName = scope?.bucket?.name
    private val scopeName = scope?.name
    private val queryContext = scope?.let { AnalyticsRequest.queryContext(scope.bucket.name, scope.name) }

    fun query(
        statement: String,
        common: CommonOptions,
        parameters: AnalyticsParameters,

        serializer: JsonSerializer?,

        consistency: AnalyticsScanConsistency,
        readonly: Boolean,
        priority: AnalyticsPriority,

        clientContextId: String?,
        raw: Map<String, Any?>,
    ): Flow<AnalyticsFlowItem> {

        val timeout = with(core.env) { common.actualAnalyticsTimeout() }

        // use interface type so less capable serializers don't freak out
        val queryJson: MutableMap<String, Any?> = HashMap<String, Any?>()

        queryJson["statement"] = statement
        queryJson["timeout"] = Golang.encodeDurationToMs(timeout)

        if (readonly) queryJson["readonly"] = true

        clientContextId?.let { queryJson["client_context_id"] = it }

        consistency.inject(queryJson)
        parameters.inject(queryJson)

        queryContext?.let { queryJson["query_context"] = it }

        queryJson.putAll(raw)

        val actualSerializer = serializer ?: core.env.jsonSerializer
        val queryBytes = actualSerializer.serialize(queryJson, typeRef())

        return flow {
            val request = with(core.env) {
                AnalyticsRequest(
                    common.actualAnalyticsTimeout(),
                    core.context(),
                    common.actualRetryStrategy(),
                    core.context().authenticator(),
                    queryBytes,
                    priority.wireValue,
                    readonly,
                    clientContextId,
                    statement,
                    common.actualSpan(TracingIdentifiers.SPAN_REQUEST_ANALYTICS),
                    bucketName,
                    scopeName,
                )
            }
            request.context().clientContext(common.clientContext)

            try {
                core.send(request)

                val response = request.response().await()

                emitAll(response.rows().asFlow()
                    .map { AnalyticsRow(it.data(), actualSerializer) })

                emitAll(response.trailer().asFlow()
                    .map { AnalyticsMetadata(response.header(), it) })

            } finally {
                request.logicallyComplete()
            }
        }
    }
}
