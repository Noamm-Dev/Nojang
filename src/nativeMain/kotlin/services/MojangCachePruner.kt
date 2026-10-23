package services

import database.repositories.MojangRepository
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

private val logger = KtorSimpleLogger("MojangCachePruner")

/**
 * Expired rows are already ignored on read, this just keeps them from piling up.
 */
class MojangCachePruner(private val repository: MojangRepository) {
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