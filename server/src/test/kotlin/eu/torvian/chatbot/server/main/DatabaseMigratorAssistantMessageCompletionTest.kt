package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the `V31__assistant_message_completion.sql` migration.
 *
 * They pin the two properties the feature depends on: the four completion columns exist with the documented
 * nullability/defaults (D4), and rows that existed before the migration keep reading as *completed*, so historical
 * interruptions stay indistinguishable from before the upgrade.
 */
class DatabaseMigratorAssistantMessageCompletionTest {

    /**
     * Column definition as reported by `PRAGMA table_info`.
     *
     * @property name Column name as declared in the migration.
     * @property type Declared SQL type (SQLite keeps the text as written).
     * @property notNull `1` when the column is declared `NOT NULL`, `0` otherwise.
     * @property defaultValue The literal default expression, or `null` when the column has no default.
     */
    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?
    )

    @Test
    fun `V31 migration should add the assistant message completion columns`() {
        val dbFile = Files.createTempFile("chatbot-migration-v31", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            assertTrue(hasVersionEntry(config.url, "31"), "V31 must be recorded in the Flyway history")

            val columns = tableColumns(config.url, "assistant_messages")

            // The completion flag is the only NOT NULL column and defaults to completed for legacy rows.
            val isComplete = assertNotNull(columns["is_complete"], "assistant_messages.is_complete must exist")
            assertEquals("BOOLEAN", isComplete.type.uppercase())
            assertEquals(1, isComplete.notNull, "is_complete must be NOT NULL")
            assertTrue(
                isComplete.defaultValue?.uppercase() in setOf("TRUE", "1"),
                "is_complete must default to completed, was '${isComplete.defaultValue}'"
            )

            // Cause, code and reason are nullable: NULL means "no terminal cause"/"no failure detail".
            val incompleteCause = assertNotNull(
                columns["incomplete_cause"],
                "assistant_messages.incomplete_cause must exist"
            )
            assertEquals("VARCHAR(50)", incompleteCause.type.uppercase())
            assertEquals(0, incompleteCause.notNull, "incomplete_cause must be nullable")
            assertEquals(null, incompleteCause.defaultValue)

            val errorCode = assertNotNull(columns["error_code"], "assistant_messages.error_code must exist")
            assertEquals("VARCHAR(50)", errorCode.type.uppercase())
            assertEquals(0, errorCode.notNull, "error_code must be nullable")
            assertEquals(null, errorCode.defaultValue)

            val errorMessage = assertNotNull(columns["error_message"], "assistant_messages.error_message must exist")
            assertEquals("TEXT", errorMessage.type.uppercase())
            assertEquals(0, errorMessage.notNull, "error_message must be nullable")
            assertEquals(null, errorMessage.defaultValue)
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V31 migration should keep pre-existing assistant rows completed`() {
        val dbFile = Files.createTempFile("chatbot-migration-v31-legacy", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            val statements = completionMigrationStatements()
            assertEquals(
                EXPECTED_ALTER_STATEMENT_COUNT,
                statements.size,
                "V31 must only contain the four additive ADD COLUMN statements"
            )
            assertTrue(
                statements.all { it.startsWith("ALTER TABLE assistant_messages ADD COLUMN") },
                "V31 must stay additive: $statements"
            )

            // Simulate a pre-V31 database: the assistant table exists and already holds a row.
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
                // The legacy row must read as completed with no cause, code or reason.
                connection.readCompletionState(1L).let { state ->
                    assertEquals(1, state.isComplete, "A legacy row must stay completed")
                    assertEquals(null, state.incompleteCause)
                    assertEquals(null, state.errorCode)
                    assertEquals(null, state.errorMessage)
                }

                // A row inserted afterwards without naming the new columns relies on the DEFAULT TRUE.
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO assistant_messages (message_id) VALUES (2)")
                }
                assertEquals(1, connection.readCompletionState(2L).isComplete)
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Reads the persisted completion state of one assistant row.
     *
     * @property isComplete Raw `is_complete` value (`1` completed, `0` not completed).
     * @property incompleteCause Stored enum name of the cause, or `null`.
     * @property errorCode Stored enum name of the failure code, or `null`.
     * @property errorMessage Stored failure reason, or `null`.
     */
    private data class PersistedCompletionState(
        val isComplete: Int,
        val incompleteCause: String?,
        val errorCode: String?,
        val errorMessage: String?
    )

    /**
     * Reads the completion columns of the given assistant row.
     *
     * @receiver An open SQLite connection to a database in which V31 was applied.
     * @param messageId Primary key of the assistant row to inspect.
     * @return The raw completion columns of that row.
     */
    private fun Connection.readCompletionState(messageId: Long): PersistedCompletionState =
        prepareStatement(
            "SELECT is_complete, incomplete_cause, error_code, error_message " +
                "FROM assistant_messages WHERE message_id = ?"
        ).use { statement ->
            statement.setLong(1, messageId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected assistant row $messageId")
                PersistedCompletionState(
                    isComplete = resultSet.getInt("is_complete"),
                    incompleteCause = resultSet.getString("incomplete_cause"),
                    errorCode = resultSet.getString("error_code"),
                    errorMessage = resultSet.getString("error_message")
                )
            }
        }

    /**
     * Reads the V31 migration script from the server classpath and returns its executable statements, with
     * comment lines stripped.
     *
     * Applying the real script (instead of a copy) guarantees the test fails if the migration content changes.
     *
     * @return The trimmed statements of the migration, in file order.
     */
    private fun completionMigrationStatements(): List<String> {
        val resourceName = "/db/migration/V31__assistant_message_completion.sql"
        val script = checkNotNull(
            DatabaseMigratorAssistantMessageCompletionTest::class.java.getResourceAsStream(resourceName)
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
                            val name = resultSet.getString("name")
                            put(
                                name,
                                ColumnInfo(
                                    name = name,
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
         * Number of `ALTER TABLE` statements the V31 migration is expected to contain.
         */
        const val EXPECTED_ALTER_STATEMENT_COUNT: Int = 4
    }
}
