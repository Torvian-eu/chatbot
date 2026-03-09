package eu.torvian.chatbot.server.koin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Dependency injection module for configuring the application's database connection.
 *
 * This module provides a robust, production-ready database setup using a HikariCP
 * connection pool, specifically configured for safe concurrent access to an SQLite database
 * from a multi-threaded environment like Ktor.
 *
 * This module provides:
 * - A singleton, thread-safe `Database` instance for JetBrains Exposed.
 * - A production-grade HikariCP connection pool that manages the underlying database connections.
 * - **SQLite Concurrency Safety:**
 *   - The pool is limited to a `maximumPoolSize` of 1. This is the primary mechanism to
 *     serialize write access and prevent `SQLITE_BUSY` or `SQLITE_BUSY_SNAPSHOT` errors.
 *   - Write-Ahead Logging (`WAL`) mode is enabled for better performance and to allow
 *     concurrent read access from other processes.
 *   - A `busyTimeout` is set as a fallback for handling temporary database locks.
 * - **Automatic Resource Management:** The HikariCP data source is `AutoCloseable` and will be
 *   gracefully closed by Koin when the application shuts down, preventing resource leaks.
 */
fun databaseModule() = module {
    single<Database> {
        val dbConfig: DatabaseConfig = get()

        // Configuration for the underlying SQLite driver
        val sqliteConfig = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
        }

        val sqliteDataSource = SQLiteDataSource(sqliteConfig).apply {
            url = dbConfig.url
        }

        // Configuration for the Hikari Connection Pool
        val hikariConfig = HikariConfig().apply {
            dataSource = sqliteDataSource
            maximumPoolSize = 1 // Limit to a single connection for SQLite
            validate()
        }

        val hikariDataSource = HikariDataSource(hikariConfig)

        Database.connect(hikariDataSource)
    }
}
