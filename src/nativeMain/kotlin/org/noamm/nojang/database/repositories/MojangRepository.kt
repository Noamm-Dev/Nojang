package org.noamm.nojang.database.repositories

import org.noamm.nojang.database.Connection
import org.noamm.nojang.database.Database
import org.noamm.nojang.models.MojangData
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class MojangRepository(private val database: Database) {
    suspend fun getByUUID(uuid: String, maxAge: Duration = MAX_AGE) = database.read { find(SELECT_BY_UUID, uuid, maxAge) }
    suspend fun getByName(name: String, maxAge: Duration = MAX_AGE) = database.read { find(SELECT_BY_NAME, name, maxAge) }
    suspend fun getByUUIDs(uuids: Collection<String>, maxAge: Duration = MAX_AGE) = database.read { uuids.mapNotNull { find(SELECT_BY_UUID, it, maxAge) } }
    suspend fun getByNames(names: Collection<String>, maxAge: Duration = MAX_AGE) = database.read { names.mapNotNull { find(SELECT_BY_NAME, it, maxAge) } }

    suspend fun isMiss(query: String, maxAge: Duration = MAX_AGE) = database.read { findMiss(query, maxAge) }
    suspend fun getMisses(queries: Collection<String>, maxAge: Duration = MAX_AGE) = database.read { queries.filter { findMiss(it, maxAge) }.toSet() }

    suspend fun save(player: MojangData) = saveAll(listOf(player))
    suspend fun saveMiss(query: String) = saveAll(emptyList(), listOf(query))

    /**
     * [misses] are normalized uuids or names mojang has no profile for.
     */
    suspend fun saveAll(players: Collection<MojangData>, misses: Collection<String> = emptyList()) {
        if (players.isEmpty() && misses.isEmpty()) return

        database.write {
            val now = Clock.System.now().toEpochMilliseconds()

            players.forEach { player ->
                val uuid = normalize(player.id)

                // names get released and reclaimed, so whoever held this name before no longer does
                query(DELETE_PREVIOUS_NAME_HOLDER) {
                    it.bindText(1, player.name)
                    it.bindText(2, uuid)
                    it.step()
                }

                query(UPSERT) {
                    it.bindText(1, uuid)
                    it.bindText(2, player.name)
                    it.bindLong(3, now)
                    it.step()
                }

                query(DELETE_MISS) {
                    it.bindText(1, uuid)
                    it.bindText(2, player.name)
                    it.step()
                }
            }

            misses.forEach { miss ->
                query(UPSERT_MISS) {
                    it.bindText(1, miss)
                    it.bindLong(2, now)
                    it.step()
                }
            }
        }
    }

    /**
     * Returns how many players and misses were deleted.
     */
    suspend fun pruneExpired(maxAge: Duration = MAX_AGE) = database.write {
        listOf(DELETE_EXPIRED_PLAYERS, DELETE_EXPIRED_MISSES).sumOf { sql ->
            query(sql) {
                it.bindLong(1, minUpdatedAt(maxAge))
                it.step()
            }
            query(SELECT_CHANGES) { it.step(); it.getLong(0).toInt() }
        }
    }

    private fun Connection.find(sql: String, value: String, maxAge: Duration) = query(sql) {
        it.bindText(1, value)
        it.bindLong(2, minUpdatedAt(maxAge))
        if (it.step()) MojangData(name = it.getText(1), id = it.getText(0)) else null
    }

    private fun Connection.findMiss(value: String, maxAge: Duration) = query(SELECT_MISS) {
        it.bindText(1, value)
        it.bindLong(2, minUpdatedAt(maxAge))
        it.step()
    }

    private fun minUpdatedAt(maxAge: Duration) = Clock.System.now().toEpochMilliseconds() - maxAge.inWholeMilliseconds

    private fun normalize(uuid: String) = uuid.replace("-", "").lowercase()

    companion object {
        private val MAX_AGE = 60.minutes

        private const val SELECT_BY_UUID = "SELECT uuid, name FROM mojang_players WHERE uuid = ? AND updated_at > ?"
        private const val SELECT_BY_NAME = "SELECT uuid, name FROM mojang_players WHERE name = ? AND updated_at > ?"
        private const val DELETE_PREVIOUS_NAME_HOLDER = "DELETE FROM mojang_players WHERE name = ? AND uuid != ?"
        private const val UPSERT = "INSERT OR REPLACE INTO mojang_players (uuid, name, updated_at) VALUES (?, ?, ?)"
        private const val SELECT_MISS = "SELECT 1 FROM mojang_misses WHERE query = ? AND updated_at > ?"
        private const val DELETE_MISS = "DELETE FROM mojang_misses WHERE query = ? OR query = ?"
        private const val UPSERT_MISS = "INSERT OR REPLACE INTO mojang_misses (query, updated_at) VALUES (?, ?)"
        private const val DELETE_EXPIRED_PLAYERS = "DELETE FROM mojang_players WHERE updated_at <= ?"
        private const val DELETE_EXPIRED_MISSES = "DELETE FROM mojang_misses WHERE updated_at <= ?"
        private const val SELECT_CHANGES = "SELECT changes()"
    }
}
