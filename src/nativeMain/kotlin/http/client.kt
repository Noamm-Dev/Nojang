@file:OptIn(ExperimentalAtomicApi::class)

package http

import engine.curl.Curl
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
import utils.activeIpv4Addresses
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger = KtorSimpleLogger("ProxyService")
private val ipRotations = ConcurrentMap<String, AtomicInt>()

fun Application.createHttpClients(): ProxyHttpClient {
    val client = ProxyHttpClient()
    monitor.subscribe(ApplicationStopped) { client.close() }
    return client
}

class ProxyHttpClient: Closeable {
    val clientPool = run {
        val realIps = activeIpv4Addresses().filter { ! isSiteLocal(it) && ! isLinkLocal(it) && ! isLoopback(it) && ! isTailscale(it) }
        val clients = realIps.distinct().map { createClient(it) }

        if (clients.isEmpty()) logger.warn("No public IPs detected for rotation, falling back to the default interface.")
        else logger.info("Detected public IPs for rotation: $realIps")

        clients.ifEmpty { listOf(createClient()) }
    }

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

        // without it curl waits forever on a hung mojang, holding an upstream permit the whole time
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
        install(ContentEncoding)
        install(ContentNegotiation) { json(JSON) }
    }

    private fun octets(ip: String) = ip.split('.').map(String::toInt)
    private fun isLoopback(ip: String) = octets(ip)[0] == 127

    private fun isSiteLocal(ip: String): Boolean {
        val (a, b) = octets(ip)
        return a == 10 || (a == 172 && b in 16 .. 31) || (a == 192 && b == 168)
    }

    private fun isLinkLocal(ip: String): Boolean {
        val (a, b) = octets(ip)
        return a == 169 && b == 254
    }

    private fun isTailscale(ip: String): Boolean {
        val (a, b) = octets(ip)
        return a == 100 && b >= 64 && b <= 127
    }
}