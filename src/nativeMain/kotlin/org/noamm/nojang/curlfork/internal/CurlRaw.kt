/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.noamm.nojang.curlfork.internal

import io.ktor.client.call.UnsupportedContentTypeException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import io.ktor.utils.io.core.BytePacketBuilder
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import libcurl.curl_slist
import org.noamm.nojang.curlfork.CurlClientEngineConfig

@OptIn(ExperimentalForeignApi::class, InternalAPI::class)
internal suspend fun HttpRequestData.toCurlRequest(
    config: CurlClientEngineConfig,
    callContext: Job,
): CurlRequestData = CurlRequestData(
    url = url.toString(),
    method = method.value,
    headers = headersToCurl(),
    content = body.toByteChannel(),
    contentLength = body.contentLength ?: headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: - 1L,
    connectTimeout = getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis,
    callContext = callContext,
    localInterface = config.localInterface,
    forceHttp1 = config.forceHttp1,
)

internal class CurlRequestData @OptIn(ExperimentalForeignApi::class) constructor(
    val url: String,
    val method: String,
    val headers: CPointer<curl_slist>,
    val content: ByteReadChannel,
    val contentLength: Long,
    val connectTimeout: Long?,
    val callContext: Job,
    val localInterface: String?,
    val forceHttp1: Boolean,
) {
    override fun toString(): String =
        "CurlRequestData(url='$url', method='$method', content: $contentLength bytes)"
}

internal class CurlResponseBuilder(
    val request: CurlRequestData,
    val bodyStartedReceiving: CompletableDeferred<Unit>,
    val responseBody: CurlHttpResponseBody
) {
    val headersBytes = BytePacketBuilder()
}

internal sealed class CurlResponseData

internal class CurlSuccess(
    val status: Int,
    val version: Long,
    val headersBytes: ByteArray,
    val responseBody: CurlHttpResponseBody
): CurlResponseData() {
    override fun toString(): String = "CurlSuccess(${HttpStatusCode.fromValue(status)})"
}

internal class CurlFail(
    val cause: Throwable
): CurlResponseData() {
    override fun toString(): String = "CurlFail($cause)"
}

@OptIn(DelicateCoroutinesApi::class)
internal suspend fun OutgoingContent.toByteChannel(): ByteReadChannel = when (this@toByteChannel) {
    is OutgoingContent.ByteArrayContent -> {
        val bytes = bytes()
        ByteReadChannel(bytes, 0, bytes.size)
    }

    is OutgoingContent.WriteChannelContent -> GlobalScope.writer(currentCoroutineContext()) {
        writeTo(channel)
    }.channel

    is OutgoingContent.ReadChannelContent -> readFrom()

    is OutgoingContent.NoContent -> ByteReadChannel.Empty

    is OutgoingContent.ContentWrapper -> delegate().toByteChannel()

    is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(this@toByteChannel)
}
