package eu.torvian.chatbot.server.domain.config

import kotlinx.io.IOException
import java.io.File
import java.util.UUID
import kotlin.let
import kotlin.takeIf
import kotlin.text.contains
import kotlin.text.isNotBlank
import kotlin.text.matches
import kotlin.text.removeSuffix

/**
 * Represents the database configuration for a SQLite database.
 *
 * @property vendor The database vendor. Only "sqlite" is supported.
 * @property type The type of SQLite DB: "file" or "memory".
 * @property filepath Optional directory path where the file should be stored.
 * @property filename Optional filename for the SQLite database file. (random name if not provided)
 * @property user Optional username (not used for SQLite but kept for compatibility).
 * @property password Optional password (not used for SQLite but kept for compatibility).
 */
data class DatabaseConfig(
    val vendor: String,
    val type: String,
    val filepath: String? = null,
    val filename: String? = null,
    val user: String? = null,
    val password: String? = null
) {
    val url: String

    init {
        require(vendor == "sqlite") { "Only sqlite is supported at the moment." }
        require(type in listOf("file", "memory")) { "Invalid database type: $type" }
        filename?.let {
            require(!it.contains('/') && !it.contains('\\')) { "Filename must not contain path separators." }
            require(it.isNotBlank()) { "Filename must not be blank." }
            require(it.matches(Regex("""^[\w\-. ]+(\.sqlite)?$"""))) {
                "Filename contains invalid characters. Only letters, digits, dashes, underscores, dots, and spaces are allowed."
            }
        }

        filepath?.let {
            require(it.matches(Regex("""^(?:[a-zA-Z]:[\\/]|./|.\\|/)?(?:[\w\- .]+[\\/])*[\w\- .]*$"""))) {
                "Filepath contains invalid characters or uses ':' incorrectly."
            }
        }

        url = when (type) {
            "memory" -> "jdbc:sqlite:file:${filenameOrRandom()}?mode=memory&cache=shared"
            "file" -> {
                val basePath = filepath?.removeSuffix("/") ?: "."
                val name = filenameOrRandom()
                val fullPath = "$basePath/$name"
                val file = File(fullPath)
                if (file.exists()) {
                    require(file.canWrite()) {
                        "Cannot write the specified file path: $fullPath. Please check file permissions."
                    }
                } else {
                    try {
                        file.createNewFile()
                    } catch (e: IOException) {
                        throw kotlinx.io.IOException("Failed to create the database file at $fullPath", e)
                    }
                }
                "jdbc:sqlite:$fullPath"
            }
            else -> throw kotlin.IllegalArgumentException("Invalid database type: $type")
        }
    }

    /**
     * Generates a random filename if the filename is not provided or is blank.
     *
     * @return The filename or a random UUID as a string.
     */
    private fun filenameOrRandom(): String =
        filename?.takeIf { it.isNotBlank() } ?: "db-${UUID.randomUUID()}.sqlite"
}