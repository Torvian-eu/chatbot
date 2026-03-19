package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.flywaydb.core.Flyway

/**
 * Runs versioned schema migrations for the server database.
 */
class DatabaseMigrator(
    private val databaseConfig: DatabaseConfig
) {
    private val logger: Logger = LogManager.getLogger(DatabaseMigrator::class.java)

    /**
     * Applies pending migrations and returns the number of executed migrations.
     */
    fun migrate(): Int {
        val flyway = Flyway.configure()
            .dataSource(
                databaseConfig.url,
                databaseConfig.user ?: "",
                databaseConfig.password ?: ""
            )
            .locations("classpath:db/migration")
            .baselineVersion("1")
            .cleanDisabled(true)
            .load()

        val result = flyway.migrate()
        logger.info(
            "Database migration completed: {} migration(s) executed, current version: {}",
            result.migrationsExecuted,
            result.targetSchemaVersion
        )

        return result.migrationsExecuted
    }
}

