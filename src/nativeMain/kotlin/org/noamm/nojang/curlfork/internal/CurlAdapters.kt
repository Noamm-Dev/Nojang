/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang.curlfork.internal

import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forEachHeader
import io.ktor.http.HttpProtocolVersion
import io.ktor.utils.io.InternalAPI
import kotlinx.cinterop.*
import libcurl.*

// These should have been CPointer<CURL> and CPointer<CURLM>, I suppose,
// but somehow cinterop tool makes them just opaque pointers.
internal typealias EasyHandle = COpaquePointer
internal typealias MultiHandle = COpaquePointer

internal fun CURLMcode.verify() {
    check(this == CURLM_OK) { "Unexpected curl verify: $errorMessage" }
}

private val CURLMcode.errorMessage: String
    get() = "${curl_multi_strerror(this)?.toKString()} (code $this)"

internal fun CURLcode.verify() {
    check(this == CURLE_OK) { "Unexpected curl verify: $errorMessage" }
}

internal val CURLcode.errorMessage: String
    get() = "${curl_easy_strerror(this)?.toKString()} (code $this)"

internal fun EasyHandle.option(option: CURLoption, optionValue: Long) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: CPointer<*>) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: String) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.getInfo(info: CURLINFO, optionValue: CPointer<*>) {
    curl_easy_getinfo(this, info, optionValue).verify()
}

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal fun HttpRequestData.headersToCurl(): CPointer<curl_slist> {
    var result: CPointer<curl_slist>? = null

    forEachHeader { key, value ->
        val header = "$key: $value"
        result = curl_slist_append(result, header)
    }

    result = curl_slist_append(result, "Expect:")
    return result !!
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal fun Long.fromCurl(): HttpProtocolVersion = when (this) {
    CURL_HTTP_VERSION_1_0.toLong() -> HttpProtocolVersion.HTTP_1_0

    CURL_HTTP_VERSION_1_1.toLong() -> HttpProtocolVersion.HTTP_1_1

    CURL_HTTP_VERSION_2_0.toLong() -> HttpProtocolVersion.HTTP_2_0

    // old curl fallback
    else -> HttpProtocolVersion.HTTP_1_1
}
