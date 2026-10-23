package org.noamm.nojang.database

/**
 * Applied in order and tracked through `PRAGMA user_version`, only ever append to this list.
 * Each migration is a list of single statements since sqlite's prepare silently ignores everything after the first one.
 */
object Migrations {
    val ALL = listOf(
        listOf(
            """
            CREATE TABLE mojang_players
            (
                uuid       TEXT    NOT NULL PRIMARY KEY,
                name       TEXT    NOT NULL COLLATE NOCASE,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX mojang_players_name ON mojang_players (name)",
        ),
        listOf(
            """
            CREATE TABLE mojang_misses
            (
                query      TEXT    NOT NULL PRIMARY KEY COLLATE NOCASE,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        ),
        listOf(
            "CREATE INDEX mojang_players_updated_at ON mojang_players (updated_at)",
            "CREATE INDEX mojang_misses_updated_at ON mojang_misses (updated_at)",
        ),
    )
}
