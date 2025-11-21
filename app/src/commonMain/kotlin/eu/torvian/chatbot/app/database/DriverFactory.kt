package eu.torvian.chatbot.app.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific driver factory for SQLDelight.
 *
 * Each platform (Desktop, Android, WASM) provides its own implementation
 * of this interface to create the appropriate SQLite driver.
 */
interface DriverFactory {
    /**
     * Creates a platform-specific SQLDelight driver.
     *
     * @return A configured SqlDriver instance
     */
    fun createDriver(): SqlDriver
}

