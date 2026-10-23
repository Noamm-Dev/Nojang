package database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.ktor.utils.io.core.Closeable

/**
 * Keeps every statement it prepares, so each query is only compiled once per connection.
 * Not thread-safe, [Database] makes sure only one coroutine uses a connection at a time.
 */
class Connection(private val connection: SQLiteConnection): Closeable {
    private val statements = mutableMapOf<String, SQLiteStatement>()

    fun <T> query(sql: String, block: (SQLiteStatement) -> T): T {
        val statement = statements.getOrPut(sql) { connection.prepare(sql) }

        // a statement that isn't reset keeps its read transaction open and blocks wal checkpoints
        return try { block(statement) }
        finally {
            statement.reset()
            statement.clearBindings()
        }
    }

    fun execute(sql: String) = connection.execSQL(sql)

    fun <T> transaction(begin: String, block: Connection.() -> T): T {
        query(begin) { it.step() }
        return runCatching { block() }
            .onSuccess { query("COMMIT") { it.step() } }
            .onFailure { query("ROLLBACK") { it.step() } }
            .getOrThrow()
    }

    override fun close() {
        statements.values.forEach { it.close() }
        connection.close()
    }
}
