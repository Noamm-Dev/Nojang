package org.noamm.nojang.services

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import org.noamm.nojang.database.repositories.MojangRepository
import org.noamm.nojang.models.MojangBatch
import org.noamm.nojang.models.MojangData
import org.noamm.nojang.services.MojangApi.Companion.BULK_NAMES_LIMIT

class MojangService(private val api: MojangApi, private val repository: MojangRepository) {
    private val logger = KtorSimpleLogger("MojangService")

    suspend fun lookup(query: String): MojangData? {
        parseUUID(query)?.let { uuid -> return repository.getByUUID(uuid) ?: fetchOrMiss(uuid) { api.fetchByUUID(uuid) } }
        parseNAME(query)?.let { name -> return repository.getByName(name) ?: fetchOrMiss(name) { api.fetchByName(name) } }
        return null
    }

    /**
     * one result per found query in request order, misses are dropped.
     * A failed upstream lookup only drops its own queries instead of failing the whole batch, isn't cached as a miss,
     * and marks the batch as incomplete.
     */
    suspend fun lookupAll(queries: List<String>): MojangBatch {
        val uuids = queries.mapNotNull(::parseUUID).distinct()
        val names = queries.mapNotNull(::parseNAME).distinct()

        val byUUID = repository.getByUUIDs(uuids).associateBy { it.id }.toMutableMap()
        val byName = repository.getByNames(names).associateBy { it.name.lowercase() }.toMutableMap()

        val uncachedUUIDs = uuids.filter { it !in byUUID }
        val uncachedNames = names.filter { it !in byName }
        val misses = repository.getMisses(uncachedUUIDs + uncachedNames)

        val lookups = coroutineScope {
            val uuidLookups = uncachedUUIDs.filter { it !in misses }.map { uuid ->
                async { orNullOnFailure(listOf(uuid)) { listOfNotNull(api.fetchByUUID(uuid)) } }
            }
            val nameLookups = uncachedNames.filter { it !in misses }.chunked(BULK_NAMES_LIMIT).map { chunk ->
                async { orNullOnFailure(chunk) { api.fetchByNames(chunk) } }
            }
            (uuidLookups + nameLookups).awaitAll()
        }
        val succeeded = lookups.filterNotNull()

        val fetched = succeeded.flatMap { it.second }
        val newMisses = succeeded.flatMap { (requested, found) ->
            requested.filter { query -> found.none { query == it.id || query == it.name.lowercase() } }
        }

        repository.saveAll(fetched, newMisses)
        fetched.forEach {
            byUUID[it.id] = it
            byName[it.name.lowercase()] = it
        }

        val players = queries.mapNotNull { query -> parseUUID(query)?.let(byUUID::get) ?: byName[query.lowercase()] }
        return MojangBatch(players, complete = succeeded.size == lookups.size)
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
    private fun parseNAME(query: String) = query.takeIf { it.length in 3 .. 16 && NAME_REGEX.matches(it) }?.lowercase()

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
        private val NAME_REGEX = Regex("^[A-Za-z0-9_]{1,16}$")
    }
}
