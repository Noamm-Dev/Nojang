/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package engine.curl

import io.ktor.client.engine.*

/**
 * A configuration for the [Curl] client engine.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.engine.curl.CurlClientEngineConfig)
 */
public class CurlClientEngineConfig : HttpClientEngineConfig() {
    /**
     * Forces proxy tunneling by setting `CURLOPT_HTTPPROXYTUNNEL`.
     */
    internal var forceProxyTunneling: Boolean = false

    /**
     * Sets path to Certificate Authority (CA) bundle using `CURLOPT_CAINFO`.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.engine.curl.CurlClientEngineConfig.caInfo)
     */
    public var caInfo: String? = null

    /**
     * Sets directory that holds Certificate Authority (CA) certificates using `CURLOPT_CAPATH`.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.engine.curl.CurlClientEngineConfig.caPath)
     */
    public var caPath: String? = null

    /**
     * Enables TLS host and certificate verification by setting the
     * `CURLOPT_SSL_VERIFYPEER` and `CURLOPT_SSL_VERIFYHOST` options.
     * Similar to `-k/--insecure` curl option.
     *
     * Setting this property to `false` is recommended only for testing purposes.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.engine.curl.CurlClientEngineConfig.sslVerify)
     */
    public var sslVerify: Boolean = true

    /**
     * Binds outgoing connections to a local interface, IP address or host using `CURLOPT_INTERFACE`.
     * Accepts the same values as the `--interface` curl option, e.g. `"203.0.113.7"`, `"eth0"`, `"if!eth0"` or `"host!203.0.113.7"`.
     */
    public var localInterface: String? = null

    /**
     * Restricts connections to HTTP/1.1 by setting `CURLOPT_HTTP_VERSION` to `CURL_HTTP_VERSION_1_1`.
     * Similar to `--http1.1` curl option.
     */
    public var forceHttp1: Boolean = false
}
