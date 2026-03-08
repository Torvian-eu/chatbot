package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback
import org.koin.dsl.module
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Dependency injection module for configuring the application's database connection.
 *
 * This module provides:
 * - A singleton instance of the `Database` class (Exposed).
 * - Persistent connection for in-memory SQLite databases.
 * - Automatic cleanup of the database connection when the Koin context is stopped.
 */
fun testDatabaseModule() = module {
    single<Database> {
        val dbConfig: DatabaseConfig = get()

        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
        }

        val dataSource = SQLiteDataSource(config).apply {
            url = dbConfig.url
        }

        // Hold open connection if using shared in-memory
        val persistentConnection = if (dbConfig.type == "memory") {
            dataSource.connection
        } else null

        // Register cleanup for the persistent connection
        registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                persistentConnection?.close()
            }
        })

        Database.connect(dataSource)
    }
}