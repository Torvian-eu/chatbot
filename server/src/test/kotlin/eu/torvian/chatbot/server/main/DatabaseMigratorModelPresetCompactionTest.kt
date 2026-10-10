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
 * Regression tests for the `V35__model_preset_compaction_config.sql` migration.
 *
 * They pin the properties the per-preset compaction configuration depends on: `automatic_compaction_enabled` is a
 * non-null column defaulting to `true` and `compaction_threshold_tokens` is a nullable `BIGINT` with no
 * default, so every row that existed before the upgrade — or is inserted without naming either column
 * — reproduces the pre-feature behaviour (compaction governed by the user preference alone).
 *
 * The second test applies the V35 script on its own, so it observes the column under its pre-rename
 * name; the rename itself is covered by `DatabaseMigratorAutomaticCompactionRenameTest`.
 */
class DatabaseMigratorModelPresetCompactionTest {

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
    fun `V35 migration should add the preset compaction columns`() {
        val dbFile = Files.createTempFile("chatbot-migration-v35", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            assertTrue(hasVersionEntry(config.url, "35"), "V35 must be recorded in the Flyway history")
            assertTrue(
                hasVersionEntry(config.url, "34"),
                "V35 must be applied after the previous migration"
            )

            val columns = tableColumns(config.url, "model_presets")
            val enabled = assertNotNull(columns["automatic_compaction_enabled"], "model_presets.automatic_compaction_enabled must exist")
            assertEquals("BOOLEAN", enabled.type.uppercase())
            assertEquals(1, enabled.notNull, "automatic_compaction_enabled must be non-null")
            assertEquals("1", enabled.defaultValue, "automatic_compaction_enabled must default to true")

            val threshold = assertNotNull(
                columns["compaction_threshold_tokens"],
                "model_presets.compaction_threshold_tokens must exist"
            )
            assertEquals("BIGINT", threshold.type.uppercase())
            assertEquals(0, threshold.notNull, "compaction_threshold_tokens must be nullable")
            assertNull(threshold.defaultValue, "compaction_threshold_tokens must have no default")
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V35 migration should stay additive and backfill legacy rows with the defaults`() {
        val dbFile = Files.createTempFile("chatbot-migration-v35-legacy", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            val statements = compactionMigrationStatements()
            assertEquals(
                EXPECTED_ALTER_STATEMENT_COUNT,
                statements.size,
                "V35 must only contain the two additive ADD COLUMN statements"
            )
            assertTrue(
                statements.all { it.startsWith("ALTER TABLE model_presets ADD COLUMN") },
                "V35 must stay additive: $statements"
            )

            // Simulate a database whose preset table predates the columns and already holds a row.
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE model_presets (id INTEGER PRIMARY KEY)")
                    statement.execute("INSERT INTO model_presets (id) VALUES (1)")
                }
            }
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statements.forEach { statement.execute(it) }
                }
            }

            DriverManager.getConnection(config.url).use { connection ->
                val legacyRow = connection.readCompactionConfig(1L)
                assertTrue(legacyRow.first, "A legacy row must read as compaction-enabled")
                assertNull(legacyRow.second, "A legacy row must read as \"use the preference threshold\"")
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO model_presets (id) VALUES (2)")
                }
                val insertedRow = connection.readCompactionConfig(2L)
                assertTrue(
                    insertedRow.first,
                    "A row inserted without the columns must read as compaction-enabled"
                )
                assertNull(
                    insertedRow.second,
                    "A row inserted without the columns must read as \"use the preference threshold\""
                )
                connection.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE model_presets SET compaction_enabled = 0, compaction_threshold_tokens = 50000 " +
                            "WHERE id = 2"
                    )
                }
                assertEquals(false to 50_000L, connection.readCompactionConfig(2L))
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Reads the raw compaction columns of the given preset row.
     *
     * The V35 column name is read here because this helper is only used on a database where the V35
     * script was applied directly, without the later rename.
     *
     * @receiver An open SQLite connection to a database in which V35 was applied.
     * @param presetId Primary key of the preset row to inspect.
     * @return The stored enabled flag paired with the stored threshold override, or `null` for the
     *         threshold when the row carries none.
     */
    private fun Connection.readCompactionConfig(presetId: Long): Pair<Boolean, Long?> =
        prepareStatement(
            "SELECT compaction_enabled, compaction_threshold_tokens FROM model_presets WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, presetId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected preset row $presetId")
                // `getLong` answers 0 for SQL NULL, so the null-ness has to be checked first.
                val threshold = resultSet.getLong("compaction_threshold_tokens")
                val storedThreshold = if (resultSet.wasNull()) null else threshold
                (resultSet.getInt("compaction_enabled") != 0) to storedThreshold
            }
        }

    /**
     * Reads the V35 migration script from the server classpath and returns its executable statements,
     * with comment lines stripped.
     *
     * Applying the real script (instead of a copy) guarantees the test fails if the migration content
     * changes.
     *
     * @return The trimmed statements of the migration, in file order.
     */
    private fun compactionMigrationStatements(): List<String> {
        val resourceName = "/db/migration/V35__model_preset_compaction_config.sql"
        val script = checkNotNull(
            DatabaseMigratorModelPresetCompactionTest::class.java.getResourceAsStream(resourceName)
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
         * Number of `ALTER TABLE` statements the V35 migration is expected to contain.
         */
        const val EXPECTED_ALTER_STATEMENT_COUNT: Int = 2
    }
}
