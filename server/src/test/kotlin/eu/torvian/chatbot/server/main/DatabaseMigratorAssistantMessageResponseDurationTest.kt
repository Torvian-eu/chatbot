package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the `V34__assistant_message_response_duration.sql` migration.
 *
 * They pin the two properties the response-duration feature depends on: the column is a nullable `BIGINT` with no
 * default, and every row that existed before it — or is inserted without naming it — reads as "no duration" rather
 * than as zero milliseconds.
 */
class DatabaseMigratorAssistantMessageResponseDurationTest {

    /**
     * Column definition as reported by `PRAGMA table_info`.
     *
     * @property type Declared SQL type (SQLite keeps the text as written).
     * @property notNull `1` when the column is declared `NOT NULL`, `0` otherwise.
     * @property defaultValue The literal default expression, or `null` when the column has no default.
     */
    private data class ColumnInfo(
        val type: String,
        val notNull: Int,
        val defaultValue: String?
    )

    @Test
    fun `V34 migration should add a nullable response duration column to assistant messages`() {
        val dbFile = Files.createTempFile("chatbot-migration-v34", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            assertTrue(hasVersionEntry(config.url, "34"), "V34 must be recorded in the Flyway history")

            val responseDuration = assertNotNull(
                tableColumns(config.url, "assistant_messages")["response_duration_ms"],
                "assistant_messages.response_duration_ms must exist"
            )
            assertEquals("BIGINT", responseDuration.type.uppercase())
            assertEquals(0, responseDuration.notNull, "response_duration_ms must be nullable")
            assertNull(responseDuration.defaultValue, "response_duration_ms must have no default")
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V34 migration should stay additive and leave existing rows without a duration`() {
        val dbFile = Files.createTempFile("chatbot-migration-v34-legacy", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            val statements = responseDurationMigrationStatements()
            assertEquals(
                EXPECTED_ALTER_STATEMENT_COUNT,
                statements.size,
                "V34 must only contain the single additive ADD COLUMN statement"
            )
            assertTrue(
                statements.all { it.startsWith("ALTER TABLE assistant_messages ADD COLUMN") },
                "V34 must stay additive: $statements"
            )

            // Simulate a database whose assistant table predates the column and already holds a row.
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE assistant_messages (message_id INTEGER PRIMARY KEY)")
                    statement.execute("INSERT INTO assistant_messages (message_id) VALUES (1)")
                }
            }
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statements.forEach { statement.execute(it) }
                }
            }

            DriverManager.getConnection(config.url).use { connection ->
                assertNull(connection.readResponseDurationMs(1L), "A legacy row must read as \"no duration\"")
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO assistant_messages (message_id) VALUES (2)")
                }
                assertNull(
                    connection.readResponseDurationMs(2L),
                    "A row inserted without the column must read as \"no duration\""
                )
                connection.createStatement().use { statement ->
                    statement.execute("UPDATE assistant_messages SET response_duration_ms = 4200 WHERE message_id = 2")
                }
                assertEquals(4200L, connection.readResponseDurationMs(2L))
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Reads the raw response-duration column of the given assistant row.
     *
     * @receiver An open SQLite connection to a database in which V34 was applied.
     * @param messageId Primary key of the assistant row to inspect.
     * @return The stored duration in milliseconds, or `null` when the row carries none.
     */
    private fun Connection.readResponseDurationMs(messageId: Long): Long? =
        prepareStatement("SELECT response_duration_ms FROM assistant_messages WHERE message_id = ?").use { statement ->
            statement.setLong(1, messageId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected assistant row $messageId")
                // `getLong` answers 0 for SQL NULL, so the null-ness has to be checked first.
                val storedValue = resultSet.getLong("response_duration_ms")
                if (resultSet.wasNull()) null else storedValue
            }
        }

    /**
     * Reads the V34 migration script from the server classpath and returns its executable statements, with
     * comment lines stripped.
     *
     * Applying the real script (instead of a copy) guarantees the test fails if the migration content changes.
     *
     * @return The trimmed statements of the migration, in file order.
     */
    private fun responseDurationMigrationStatements(): List<String> {
        val resourceName = "/db/migration/V34__assistant_message_response_duration.sql"
        val script = checkNotNull(
            DatabaseMigratorAssistantMessageResponseDurationTest::class.java.getResourceAsStream(resourceName)
        ) { "Migration resource $resourceName must be on the classpath" }.bufferedReader().use { it.readText() }

        return script.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Reads the declared columns of a table via `PRAGMA table_info`.
     *
     * @param url JDBC URL of the database to inspect.
     * @param tableName Table whose columns should be returned.
     * @return Column definitions keyed by column name.
     */
    private fun tableColumns(url: String, tableName: String): Map<String, ColumnInfo> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
                    buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getString("name"),
                                ColumnInfo(
                                    type = resultSet.getString("type"),
                                    notNull = resultSet.getInt("notnull"),
                                    defaultValue = resultSet.getString("dflt_value")
                                )
                            )
                        }
                    }
                }
            }
        }

    /**
     * Checks for a Flyway history entry with the given version.
     *
     * @param url JDBC URL of the migrated database.
     * @param version Version string to look up.
     * @return `true` when the version was applied.
     */
    private fun hasVersionEntry(url: String, version: String): Boolean =
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("SELECT 1 FROM flyway_schema_history WHERE version = ?").use { statement ->
                statement.setString(1, version)
                statement.executeQuery().use { resultSet -> resultSet.next() }
            }
        }

    private companion object {
        /**
         * Number of `ALTER TABLE` statements the V34 migration is expected to contain.
         */
        const val EXPECTED_ALTER_STATEMENT_COUNT: Int = 1
    }
}
