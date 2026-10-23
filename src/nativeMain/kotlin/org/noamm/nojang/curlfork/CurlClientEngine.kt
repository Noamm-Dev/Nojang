/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.noamm.nojang.curlfork

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.utils.dropCompressionHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.parseHeaders
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.*
import kotlinx.coroutines.job
import org.noamm.nojang.curlfork.internal.fromCurl
import org.noamm.nojang.curlfork.internal.toCurlRequest

internal class CurlClientEngine(
    override val config: CurlClientEngineConfig
): HttpClientEngineBase("ktor-curl") {

    override val supportedCapabilities = setOf(HttpTimeoutCapability)
    private val curlProcessor = CurlProcessor(coroutineContext)

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()

        val curlRequest = data.toCurlRequest(config, callContext.job)
        val responseData = curlProcessor.executeRequest(curlRequest)

        return with(responseData) {
            val headerBytes = ByteReadChannel(headersBytes).apply { readLineStrict() }
            val rawHeaders = parseHeaders(headerBytes)
            val headers = rawHeaders.toBuilder().apply {
                dropCompressionHeaders(data.method, data.attributes)
            }.build()

            rawHeaders.release()

            HttpResponseData(
                HttpStatusCode.fromValue(status),
                requestTime,
                headers,
                version.fromCurl(),
                responseBody.bodyChannel,
                callContext
            )
        }
    }

    override fun close() {
        super.close()
        curlProcessor.close()
    }
}