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
 * Regression tests for the `V36__rename_model_preset_automatic_compaction.sql` migration.
 *
 * The rename must be purely mechanical: the column keeps its type, its `NOT NULL DEFAULT 1` definition
 * and every stored value, and no other column or row of `model_presets` changes. Only the preset scope
 * is migrated — no stored preference row and no persisted tool schema is rewritten.
 */
class DatabaseMigratorAutomaticCompactionRenameTest {

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
    fun `V36 migration should rename the preset compaction flag preserving its definition`() {
        val dbFile = Files.createTempFile("chatbot-migration-v36", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            assertTrue(hasVersionEntry(config.url, "36"), "V36 must be recorded in the Flyway history")
            assertTrue(hasVersionEntry(config.url, "35"), "V36 must be applied after V35")

            val columns = tableColumns(config.url, "model_presets")
            assertNull(
                columns["compaction_enabled"],
                "model_presets.compaction_enabled must no longer exist after the rename"
            )
            val renamed = assertNotNull(
                columns["automatic_compaction_enabled"],
                "model_presets.automatic_compaction_enabled must exist after the rename"
            )
            assertEquals("BOOLEAN", renamed.type.uppercase())
            assertEquals(1, renamed.notNull, "automatic_compaction_enabled must stay non-null")
            assertEquals("1", renamed.defaultValue, "automatic_compaction_enabled must stay default true")
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V36 migration should move stored values to the renamed column without touching the rest`() {
        val dbFile = Files.createTempFile("chatbot-migration-v36-rename", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            val statement = renameMigrationStatement()
            assertEquals(
                "ALTER TABLE model_presets RENAME COLUMN compaction_enabled TO automatic_compaction_enabled",
                statement,
                "V36 must contain exactly the column rename"
            )

            // Simulate a database whose preset table still uses the pre-rename column and already holds
            // one row per stored value, plus a row (id 3) that never names either compaction column so
            // the default must decide it.
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute(
                        "CREATE TABLE model_presets (" +
                            "id INTEGER PRIMARY KEY, " +
                            "name TEXT NOT NULL, " +
                            "description TEXT NOT NULL DEFAULT '', " +
                            "compaction_enabled BOOLEAN NOT NULL DEFAULT 1, " +
                            "compaction_threshold_tokens BIGINT, " +
                            "created_at BIGINT NOT NULL DEFAULT 0, " +
                            "updated_at BIGINT NOT NULL DEFAULT 0)"
                    )
                    sql.execute(
                        "INSERT INTO model_presets (id, name, compaction_enabled, compaction_threshold_tokens) " +
                            "VALUES (1, 'off', 0, 50000)"
                    )
                    sql.execute(
                        "INSERT INTO model_presets (id, name, compaction_enabled, compaction_threshold_tokens) " +
                            "VALUES (2, 'on', 1, NULL)"
                    )
                    sql.execute("INSERT INTO model_presets (id, name) VALUES (3, 'defaulted')")
                }
            }
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { sql -> sql.execute(statement) }
            }

            DriverManager.getConnection(config.url).use { connection ->
                // A stored `false` stays false and a stored `true` stays true.
                assertEquals(false to 50_000L, connection.readPreset(1L))
                assertEquals(true to null, connection.readPreset(2L))
                // A row that relied on the column default still reads as compaction-allowed.
                assertEquals(true to null, connection.readPreset(3L))
                // Sibling columns and every row survive the rename.
                assertEquals(listOf("off", "on", "defaulted"), connection.readPresetNames())
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Reads the renamed compaction flag and the threshold override of one preset row.
     *
     * @receiver An open SQLite connection in which the rename was applied.
     * @param presetId Primary key of the preset row to inspect.
     * @return The stored flag paired with the stored threshold override, or `null` for the threshold
     *         when the row carries none.
     */
    private fun Connection.readPreset(presetId: Long): Pair<Boolean, Long?> =
        prepareStatement(
            "SELECT automatic_compaction_enabled, compaction_threshold_tokens FROM model_presets WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, presetId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected preset row $presetId")
                // `getLong` answers 0 for SQL NULL, so the null-ness has to be checked first.
                val threshold = resultSet.getLong("compaction_threshold_tokens")
                val storedThreshold = if (resultSet.wasNull()) null else threshold
                (resultSet.getInt("automatic_compaction_enabled") != 0) to storedThreshold
            }
        }

    /**
     * Reads every preset name in primary-key order.
     *
     * @receiver An open SQLite connection in which the rename was applied.
     * @return The stored names, ordered by id.
     */
    private fun Connection.readPresetNames(): List<String> =
        createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM model_presets ORDER BY id").use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString("name"))
                }
            }
        }

    /**
     * Reads the V36 migration script from the server classpath and returns its single statement.
     *
     * Applying the real script (instead of a copy) guarantees the test fails if the migration content
     * changes.
     *
     * @return The trimmed statement of the migration.
     */
    private fun renameMigrationStatement(): String {
        val resourceName = "/db/migration/V36__rename_model_preset_automatic_compaction.sql"
        val script = checkNotNull(
            DatabaseMigratorAutomaticCompactionRenameTest::class.java.getResourceAsStream(resourceName)
        ) { "Migration resource $resourceName must be on the classpath" }.bufferedReader().use { it.readText() }

        val statements = script.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        assertEquals(1, statements.size, "V36 must contain exactly one statement")
        return statements.single()
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
}
