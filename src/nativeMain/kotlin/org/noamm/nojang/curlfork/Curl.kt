/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package org.noamm.nojang.curlfork

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import libcurl.CURL_GLOBAL_ALL
import libcurl.curl_global_init

// This function is thread unsafe!
// The man page asks to run it once per program,
// while the program "is still single threaded", explicitly stating that
// it should not called while any other thread is running.
// See the curl_global_init(3) man page for details.
@Suppress("DEPRECATION")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val curlGlobalInitReturnCode = curlInitBridge()

@OptIn(ExperimentalForeignApi::class)
internal fun curlInitBridge(): Int = curl_global_init(CURL_GLOBAL_ALL.convert()).convert()

/**
 * Trimmed fork of Ktor's Curl engine: plain HTTP requests only, plus [CurlClientEngineConfig.localInterface]
 * and [CurlClientEngineConfig.forceHttp1].
 */
data object Curl: HttpClientEngineFactory<CurlClientEngineConfig> {
    override fun create(block: CurlClientEngineConfig.() -> Unit): HttpClientEngine {
        if (curlGlobalInitReturnCode != 0) throw RuntimeException("curl_global_init() returned non-zero verify: $curlGlobalInitReturnCode")
        return CurlClientEngine(CurlClientEngineConfig().apply(block))
    }
}
