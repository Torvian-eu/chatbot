package eu.torvian.chatbot.server.domain.config

import java.io.File

/**
 * Represents the database configuration for a SQLite database.
 *
 * @property vendor The database vendor. Only "sqlite" is supported.
 * @property type The type of SQLite DB: "file" or "memory".
 * @property filepath Full path to the database file (e.g. "/opt/chatbot/data/chatbot.db").
 *                    Always resolved from [StorageConfig.databasePath].
 * @property user Optional username (not used for SQLite but kept for compatibility).
 * @property password Optional password (not used for SQLite but kept for compatibility).
 */
data class DatabaseConfig(
    val vendor: String,
    val type: String,
    val filepath: String,
    val user: String? = null,
    val password: String? = null
) {
    /**
     * The full JDBC connection URL for the database.
     */
    val url: String

    init {
        require(vendor == "sqlite") { "Only 'sqlite' is supported at the moment." }
        require(type in listOf("file", "memory")) { "Invalid database type: $type" }
        require(filepath.isNotBlank()) { "filepath must not be blank." }

        url = when (type) {
            "memory" -> "jdbc:sqlite:file:${File(filepath).name}?mode=memory&cache=shared"
            "file"   -> "jdbc:sqlite:$filepath"
            else     -> throw IllegalArgumentException("Invalid database type: $type")
        }
    }
}