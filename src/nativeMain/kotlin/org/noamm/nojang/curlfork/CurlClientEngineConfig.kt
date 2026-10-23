/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package org.noamm.nojang.curlfork

import io.ktor.client.engine.HttpClientEngineConfig

/**
 * A configuration for the [Curl] client engine.
 */
class CurlClientEngineConfig: HttpClientEngineConfig() {
    /**
     * Binds outgoing connections to a local interface, IP address or host using `CURLOPT_INTERFACE`.
     * Accepts the same values as the `--interface` curl option, e.g. `"203.0.113.7"`, `"eth0"`, `"if!eth0"` or `"host!203.0.113.7"`.
     */
    var localInterface: String? = null

    /**
     * Restricts connections to HTTP/1.1 by setting `CURLOPT_HTTP_VERSION` to `CURL_HTTP_VERSION_1_1`.
     * Similar to `--http1.1` curl option.
     */
    var forceHttp1: Boolean = false
}
