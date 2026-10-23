package org.noamm.nojang.services

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import org.noamm.nojang.database.repositories.MojangRepository
import kotlin.time.Duration.Companion.hours

/**
 * Expired rows are already ignored on read, this just keeps them from piling up.
 */
class MojangCachePruner(private val repository: MojangRepository) {
    private val logger = KtorSimpleLogger("MojangCachePruner")
    private val pruneInterval = 1.hours

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val result = runCatching { repository.pruneExpired() }
            result.onSuccess { if (it > 0) logger.info("Pruned $it expired mojang cache rows") }
            result.onFailure { logger.error("Mojang cache prune failed", it) }
            delay(pruneInterval)
        }
    }
}