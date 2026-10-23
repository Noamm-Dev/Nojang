package services

import database.repositories.MojangRepository
import database.repositories.MojangRepository.MojangData
import http.ProxyHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val logger = KtorSimpleLogger("MojangService")

class MojangService(private val http: ProxyHttpClient, private val repository: MojangRepository) {
    private val upstreamLimit = Semaphore(16)

    suspend fun lookup(query: String): MojangData? {
        parseUUID(query)?.let { uuid ->
            return repository.getByUUID(uuid) ?: fetchOrMiss(uuid) { fetchByUUID(uuid) }
        }

        if (! isName(query)) return null
        return repository.getByName(query) ?: fetchOrMiss(query) { fetchByName(query) }
    }

    /**
     * Mirrors mowojang's batch endpoint, one result per found query in request order, misses are dropped.
     * A failed upstream lookup only drops its own queries instead of failing the whole batch, and isn't cached as a miss.
     */
    suspend fun lookupAll(queries: List<String>): List<MojangData> {
        val uuids = queries.mapNotNull(::parseUUID).distinct()
        val names = queries.filter(::isName).map(String::lowercase).distinct()

        val byUUID = repository.getByUUIDs(uuids).associateBy { it.id }.toMutableMap()
        val byName = repository.getByNames(names).associateBy { it.name.lowercase() }.toMutableMap()

        val uncachedUUIDs = uuids.filter { it !in byUUID }
        val uncachedNames = names.filter { it !in byName }
        val misses = repository.getMisses(uncachedUUIDs + uncachedNames)

        val lookups = coroutineScope {
            val uuidLookups = uncachedUUIDs.filter { it !in misses }.map { uuid ->
                async { orNullOnFailure(listOf(uuid)) { listOfNotNull(fetchByUUID(uuid)) } }
            }
            val nameLookups = uncachedNames.filter { it !in misses }.chunked(BULK_NAMES_LIMIT).map { chunk ->
                async { orNullOnFailure(chunk) { fetchByNames(chunk) } }
            }
            (uuidLookups + nameLookups).awaitAll().filterNotNull()
        }

        val fetched = lookups.flatMap { it.second }
        val newMisses = lookups.flatMap { (requested, found) ->
            requested.filter { query -> found.none { query == it.id || query == it.name.lowercase() } }
        }

        repository.saveAll(fetched, newMisses)
        fetched.forEach {
            byUUID[it.id] = it
            byName[it.name.lowercase()] = it
        }

        return queries.mapNotNull { query -> parseUUID(query)?.let(byUUID::get) ?: byName[query.lowercase()] }
    }

    private suspend fun fetchByUUID(uuid: String) = request<MojangData>("sessionserver") {
        get("https://sessionserver.mojang.com/session/minecraft/profile/$uuid")
    }

    private suspend fun fetchByName(name: String) = request<MojangData>("profiles") {
        get("https://api.mojang.com/users/profiles/minecraft/$name")
    }

    private suspend fun fetchByNames(names: List<String>) = request<List<MojangData>>("bulk") {
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
        repeat(http.clientPool.size) {
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

    private suspend fun fetchOrMiss(query: String, fetch: suspend () -> MojangData?): MojangData? {
        if (repository.isMiss(query)) return null

        val player = fetch()
        if (player == null) repository.saveMiss(query) else repository.save(player)
        return player
    }

    private suspend fun orNullOnFailure(requested: List<String>, block: suspend () -> List<MojangData>) = runCatching { requested to block() }.getOrElse {
        logger.warn("Mojang lookup failed", it)
        null
    }

    private fun parseUUID(query: String) = query.takeIf(UUID_REGEX::matches)?.replace("-", "")?.lowercase()

    private fun isName(query: String) = NAME_REGEX.matches(query)

    companion object {
        private const val BULK_NAMES_LIMIT = 10
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
        private val NAME_REGEX = Regex("^[A-Za-z0-9_]{1,16}$")
    }
}
