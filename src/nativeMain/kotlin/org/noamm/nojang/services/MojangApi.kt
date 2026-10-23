package org.noamm.nojang.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.noamm.nojang.http.ProxyHttpClient
import org.noamm.nojang.models.MojangData

class MojangApi(private val http: ProxyHttpClient) {
    private val upstreamLimit = Semaphore(16)

    suspend fun fetchByUUID(uuid: String) = request<MojangData>("sessionserver") {
        get("https://sessionserver.mojang.com/session/minecraft/profile/$uuid")
    }

    suspend fun fetchByName(name: String) = request<MojangData>("profiles") {
        get("https://api.minecraftservices.com/minecraft/profile/lookup/name/$name")
    }

    suspend fun fetchByNames(names: List<String>) = request<List<MojangData>>("bulk") {
        post("https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname") {
            contentType(ContentType.Application.Json)
            setBody(names)
        }
    }.orEmpty()

    /**
     * Each mojang endpoint has its own ratelimit, so every one rotates through the ips separately
     * and a 429 just moves on to the next ip.
     */
    private suspend inline fun <reified T> request(rotation: String, crossinline block: suspend HttpClient.() -> HttpResponse): T? = upstreamLimit.withPermit {
        repeat(http.size) {
            val response = http.getClient(rotation).block()

            when {
                response.status == HttpStatusCode.TooManyRequests -> return@repeat
                response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.NotFound -> return@withPermit null
                ! response.status.isSuccess() -> error("Mojang responded with ${response.status} on $rotation")
                else -> return@withPermit response.body<T>()
            }
        }

        error("Mojang ratelimited every ip on $rotation")
    }

    companion object {
        const val BULK_NAMES_LIMIT = 10
    }
}
