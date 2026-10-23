@file:OptIn(ExperimentalAtomicApi::class)

package org.noamm.nojang.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.collections.ConcurrentMap
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.noamm.nojang.curlfork.Curl
import org.noamm.nojang.utils.publicIpv4Addresses
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

fun Application.createHttpClient(json: Json): ProxyHttpClient {
    val client = ProxyHttpClient(json)
    monitor.subscribe(ApplicationStopped) { client.close() }
    return client
}

class ProxyHttpClient(val json: Json): Closeable {
    private val logger = KtorSimpleLogger("ProxyHttpClient")
    private val ipRotations = ConcurrentMap<String, AtomicInt>()

    private val clientPool = run {
        val realIps = publicIpv4Addresses()
        val clients = realIps.map(::createClient)

        if (clients.isEmpty()) logger.warn("No public IPs detected for rotation, falling back to the default interface.")
        else logger.info("Detected public IPs for rotation: $realIps")

        clients.ifEmpty { listOf(createClient()) }
    }

    val size get() = clientPool.size

    fun getClient(rotationId: String): HttpClient {
        val atomicI = ipRotations.computeIfAbsent(rotationId) { AtomicInt(0) }
        val index = atomicI.fetchAndAdd(1).and(0x7FFFFFFF) % clientPool.size
        return clientPool[index]
    }

    override fun close() = runBlocking {
        clientPool.forEach { client ->
            try {
                client.close()
                client.coroutineContext.job.join()
            }
            catch (e: Exception) {
                logger.error("Error closing client during shutdown", e)
            }
        }
    }

    private fun createClient(localAddress: String? = null) = HttpClient(Curl) {
        engine {
            // "host!" makes curl treat it as an address instead of trying it as an interface name first
            localInterface = localAddress?.let { "host!$it" }
            forceHttp1 = true
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
        install(ContentEncoding)
        install(ContentNegotiation) { json(json) }
    }
}
