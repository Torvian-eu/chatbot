package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseMigratorTest {

    @Test
    fun `migrate should create Flyway history table on a fresh database`() {
        val dbFile = Files.createTempFile("chatbot-migration-fresh", ".db")
        try {
            val config = DatabaseConfig(
                vendor = "sqlite",
                type = "file",
                filepath = dbFile.toString()
            )

            val migrator = DatabaseMigrator(config)
            migrator.migrate()

            assertTrue(hasTable(config.url, "flyway_schema_history"))
            assertTrue(hasVersionEntry(config.url, "1"))
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `migrate should fail on an existing non-empty database without Flyway history`() {
        val dbFile = Files.createTempFile("chatbot-migration-existing", ".db")
        try {
            val config = DatabaseConfig(
                vendor = "sqlite",
                type = "file",
                filepath = dbFile.toString()
            )

            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE existing_table (id INTEGER PRIMARY KEY)")
                }
            }

            val migrator = DatabaseMigrator(config)
            assertFailsWith<FlywayException> {
                migrator.migrate()
            }

            assertTrue(hasTable(config.url, "existing_table"))
            assertFalse(hasTable(config.url, "flyway_schema_history"))
        } finally {
            dbFile.deleteIfExists()
        }
    }

    private fun hasTable(url: String, tableName: String): Boolean {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
        }
    }

    private fun hasVersionEntry(url: String, version: String): Boolean {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM flyway_schema_history WHERE version = ?"
            ).use { statement ->
                statement.setString(1, version)
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
        }
    }
}

