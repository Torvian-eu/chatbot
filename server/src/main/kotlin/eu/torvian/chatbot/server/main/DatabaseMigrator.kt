package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.flywaydb.core.Flyway

/**
 * Runs versioned schema migrations for the server database.
 *
 * Migrations are plain SQL. Rebuild migrations (e.g. `V10`, `V20`) drop and rename a parent table to
 * work around SQLite's lack of `ALTER TABLE ... DROP COLUMN` for foreign-key columns; the official
 * 12-step procedure requires `PRAGMA foreign_keys=OFF` while the old table is dropped so the implicit
 * DELETE performed by `DROP TABLE` cannot cascade into child tables. These migrations are safe because
 * migration connections open with foreign key enforcement OFF by default (the xerial SQLite driver
 * default — `DatabaseMigrator` never enables it), and the pragma cannot be toggled inside the script
 * itself (Flyway rejects mixing it with transactional DDL, and it is a no-op inside a transaction).
 *
 * Runtime connections (see `databaseModule`) deliberately DO enforce foreign keys.
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
