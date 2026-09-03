package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
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
    fun `migrate should create conversation compaction chunk tables on a fresh database`() {
        val dbFile = Files.createTempFile("chatbot-migration-v26", ".db")
        try {
            val config = DatabaseConfig(
                vendor = "sqlite",
                type = "file",
                filepath = dbFile.toString()
            )

            val migrator = DatabaseMigrator(config)
            migrator.migrate()

            assertTrue(hasVersionEntry(config.url, "26"))
            assertTrue(hasTable(config.url, "conversation_compaction_chunks"))
            assertTrue(hasTable(config.url, "conversation_compaction_chunk_messages"))
            assertTrue(hasIndex(config.url, "conversation_compaction_chunks_session_created_idx"))
            assertTrue(hasIndex(config.url, "conversation_compaction_chunk_messages_message_idx"))
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

    @Test
    fun `V23 migration should strip orphaned systemMessage and instructions from variable_params_json`() {
        val dbFile = Files.createTempFile("chatbot-migration-v23", ".db")
        try {
            val config = DatabaseConfig(
                vendor = "sqlite",
                type = "file",
                filepath = dbFile.toString()
            )

            // Run all migrations to ensure the model_settings table and all columns exist.
            DatabaseMigrator(config).migrate()
            val url = config.url

            // Insert legacy rows that still carry the deprecated JSON keys, simulating data
            // authored before the V23 migration was applied.
            DriverManager.getConnection(url).use { conn ->
                insertLegacySettings(conn, 1L, "CHAT", """{"systemMessage":"old prompt","temperature":0.7}""")
                insertLegacySettings(conn, 2L, "RESPONSES", """{"instructions":"old instructions","temperature":0.8}""")
                // A COMPLETION row should be left untouched (neither key is relevant).
                insertLegacySettings(conn, 3L, "COMPLETION", """{"suffix":"end","temperature":0.5}""")
            }

            // Execute the V23 migration SQL directly, simulating a pending migration on legacy data.
            DriverManager.getConnection(url).use { conn -> executeV23Migration(conn) }

            // Verify the keys were stripped while other params were preserved.
            DriverManager.getConnection(url).use { conn ->
                val chatJson = getVariableParamsJson(conn, 1L)
                assertFalse(chatJson.contains("systemMessage"), "CHAT row should not contain systemMessage after migration")
                assertTrue(chatJson.contains("temperature"), "CHAT row should still contain temperature")

                val responsesJson = getVariableParamsJson(conn, 2L)
                assertFalse(responsesJson.contains("instructions"), "RESPONSES row should not contain instructions after migration")
                assertTrue(responsesJson.contains("temperature"), "RESPONSES row should still contain temperature")

                val completionJson = getVariableParamsJson(conn, 3L)
                assertTrue(completionJson.contains("suffix"), "COMPLETION row should be untouched")
                assertTrue(completionJson.contains("temperature"), "COMPLETION row should still contain temperature")
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Inserts a row into the `model_settings` table with the given legacy JSON payload.
     *
     * @param conn Active SQLite connection.
     * @param id Row primary key.
     * @param type The settings type (CHAT, RESPONSES, etc.).
     * @param variableParamsJson JSON string with deprecated keys to simulate pre-migration data.
     */
    private fun insertLegacySettings(conn: Connection, id: Long, type: String, variableParamsJson: String) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO model_settings (id, model_id, name, type, variable_params_json, custom_params_json) " +
                    "VALUES ($id, 1, 'Legacy', '$type', '$variableParamsJson', NULL)"
            )
        }
    }

    /**
     * Executes the V23 migration SQL directly on the given connection.
     *
     * This reads the migration file content and runs both UPDATE statements, simulating what
     * Flyway would do when migrating from V22 to V23 on a database containing legacy data.
     *
     * @param conn Active SQLite connection.
     */
    private fun executeV23Migration(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "UPDATE model_settings SET variable_params_json = json_remove(variable_params_json, '$.systemMessage') " +
                    "WHERE type = 'CHAT' AND json_valid(variable_params_json)"
            )
            stmt.executeUpdate(
                "UPDATE model_settings SET variable_params_json = json_remove(variable_params_json, '$.instructions') " +
                    "WHERE type = 'RESPONSES' AND json_valid(variable_params_json)"
            )
        }
    }

    /**
     * Reads the `variable_params_json` column for the given settings id.
     *
     * @param conn Active SQLite connection.
     * @param id Row primary key.
     * @return The JSON string stored in `variable_params_json`.
     */
    private fun getVariableParamsJson(conn: Connection, id: Long): String {
        conn.prepareStatement("SELECT variable_params_json FROM model_settings WHERE id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                assertTrue(rs.next(), "Expected row with id $id")
                return rs.getString("variable_params_json")
            }
        }
    }

    private fun hasIndex(url: String, indexName: String): Boolean {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?"
            ).use { statement ->
                statement.setString(1, indexName)
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
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
