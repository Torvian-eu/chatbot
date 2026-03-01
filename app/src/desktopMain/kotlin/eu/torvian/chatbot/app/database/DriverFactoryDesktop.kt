package eu.torvian.chatbot.app.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.*

/**
 * Desktop (JVM) implementation of DriverFactory.
 *
 * Uses JDBC SQLite driver for JVM platform.
 * Enables foreign key constraints for referential integrity.
 *
 * @property databasePath The file path where the SQLite database will be stored
 */
class DriverFactoryDesktop(private val databasePath: String) : DriverFactory {

    /**
     * Creates a JDBC SQLite driver for desktop platform.
     *
     * This method:
     * - Ensures the parent directory exists
     * - Enables foreign key constraints
     * - Database migrations are handled by SQLDelight
     *
     * @return A configured SqlDriver instance
     * @throws IllegalStateException if the parent directory cannot be created or accessed
     */
    override fun createDriver(): SqlDriver {
        val databaseFile = File(databasePath)
        val parentDir = databaseFile.parentFile
            ?: throw IllegalStateException("Database path must have a parent directory")

        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw IllegalStateException("Failed to create parent directory: ${parentDir.path}")
            }
        }

        // Create driver with foreign key constraints enabled
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:$databasePath",
            properties = Properties().apply {
                put("foreign_keys", "true")  // Enable foreign key constraint enforcement
            },
            schema = LocalDatabase.Schema.synchronous()
        )
        return driver
    }
}
