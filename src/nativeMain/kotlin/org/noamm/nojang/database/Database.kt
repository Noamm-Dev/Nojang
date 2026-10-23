@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang.database

import androidx.sqlite.driver.bundled.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.utils.io.core.Closeable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.getenv

fun Application.configureDatabase(): Database {
    val database = Database(getenv("DATABASE_PATH")?.toKString() ?: "nojang.db")
    monitor.subscribe(ApplicationStopped) { database.close() }
    return database
}

/**
 * One writer guarded by a mutex since sqlite only allows a single writer anyway,
 * and a pool of read-only connections that WAL lets run alongside it.
 */
class Database(path: String, readers: Int = 4): Closeable {
    private val driver = BundledSQLiteDriver()

    // the writer has to go first, it creates the file and switches it to WAL before any reader opens it
    private val writer = Connection(driver.open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)).apply {
        execute("PRAGMA journal_mode = WAL")
        execute("PRAGMA synchronous = NORMAL")
        execute("PRAGMA busy_timeout = 5000")
        migrate()
    }
    private val writeLock = Mutex()

    private val readerConnections = List(readers) {
        Connection(driver.open(path, SQLITE_OPEN_READONLY)).apply { execute("PRAGMA busy_timeout = 5000") }
    }

    // a reception canceled right as it got a reader hands it here instead of losing it
    private val readerPool = Channel<Connection>(readers, onUndeliveredElement = { returnReader(it) })
        .apply { readerConnections.forEach(::trySend) }

    suspend fun <T> read(block: Connection.() -> T): T {
        val reader = readerPool.receive()
        try {
            return withContext(Dispatchers.IO) { reader.transaction("BEGIN", block) }
        }
        finally {
            returnReader(reader)
        }
    }

    suspend fun <T> write(block: Connection.() -> T) = withContext(Dispatchers.IO) {
        writeLock.withLock { writer.transaction("BEGIN IMMEDIATE", block) }
    }

    override fun close() {
        readerPool.close()
        readerConnections.forEach { it.close() }
        writer.close()
    }

    private fun returnReader(reader: Connection) {
        readerPool.trySend(reader)
    }

    private fun Connection.migrate() {
        val version = query("PRAGMA user_version") { it.step(); it.getLong(0).toInt() }

        Migrations.ALL.drop(version).forEachIndexed { i, statements ->
            transaction("BEGIN IMMEDIATE") {
                statements.forEach { execute(it) }
                execute("PRAGMA user_version = ${version + i + 1}")
            }
        }
    }
}