package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migration regression tests for the shareable-instruction schema change (V32).
 *
 * These tests exercise the backfill contract on real SQLite databases created from the migration
 * scripts: one instruction row per stored JSON entry (no de-duplication) with ordered links and
 * copied ownership, lenient degradation of unparseable column values, storage-level link integrity
 * on runtime connections, and the Java-migration discovery (Flyway must list version 32).
 */
class DatabaseMigratorInstructionsTest {

    @Test
    fun `V32 backfills one row per stored entry with ordered links and copied owners`() {
        val dbFile = Files.createTempFile("chatbot-instructions-backfill", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            // Build a V31 database: `agent_roles.instructions_json` still exists there.
            flywayFor(config.url, target = "31").migrate()

            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    // Role 1 (owned) carries three entries, the last two with IDENTICAL content.
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', " +
                            """'[{"type":"role","name":"Role","message":"Architect"},""" +
                            """{"type":"custom","name":"Tone","message":"Be concise"},""" +
                            """{"type":"custom","name":"Tone","message":"Be concise"}]', 0, 0)"""
                    )
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (1, 1)")
                    // Role 2 (owner-less inconsistency) carries a model_specific entry with custom JSON
                    // and a spawnable_agents entry whose wire message must NOT be stored.
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (2, 'reviewer', '', " +
                            """'[{"type":"model_specific","name":"Swift","message":"Write Swift","custom":{"modelId":7}},""" +
                            """{"type":"spawnable_agents","name":"Available agents","message":"generated junk"}]', 0, 0)"""
                    )
                }
            }

            // Migrate the V31 database to the latest schema (V32 runs here).
            flywayFor(config.url).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertFalse(
                    hasColumn(connection, "agent_roles", "instructions_json"),
                    "the denormalized JSON column must be dropped"
                )
                // 1:1 per stored entry: 3 + 2 rows, including the two identical entries of role 1.
                assertEquals(5, countRows(connection, "SELECT COUNT(*) FROM instructions"))
                assertEquals(5, countRows(connection, "SELECT COUNT(*) FROM agent_role_instructions"))

                // Role links keep the stored list order as contiguous zero-based sequence values.
                assertEquals(
                    listOf("Role", "Tone", "Tone"),
                    queryStrings(
                        connection,
                        "SELECT i.name FROM agent_role_instructions l JOIN instructions i ON i.id = l.instruction_id " +
                            "WHERE l.agent_role_id = 1 ORDER BY l.sequence"
                    )
                )
                assertEquals(
                    listOf(0, 1, 2),
                    queryInts(
                        connection,
                        "SELECT l.sequence FROM agent_role_instructions l " +
                            "WHERE l.agent_role_id = 1 ORDER BY l.sequence"
                    )
                )

                // `custom` JSON text survives verbatim; the spawnable_agents message is normalized to
                // NULL because it is regenerated per role at read time.
                assertEquals(
                    """{"modelId":7}""",
                    queryStrings(
                        connection,
                        "SELECT custom FROM instructions WHERE type = 'model_specific'"
                    ).single()
                )
                assertNull(
                    queryNullables(
                        connection,
                        "SELECT message FROM instructions WHERE type = 'spawnable_agents'"
                    ).single()
                )

                // Ownership mirrors agent_role_owners: role 1's rows are owned, role 2's owner-less
                // source rows stay owner-less (no content lost).
                assertEquals(3, countRows(connection, "SELECT COUNT(*) FROM instruction_owners"))
                assertEquals(
                    0,
                    countRows(
                        connection,
                        "SELECT COUNT(*) FROM instruction_owners o JOIN agent_role_instructions l " +
                            "ON l.instruction_id = o.instruction_id WHERE l.agent_role_id = 2"
                    )
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V32 degrades unparseable instruction arrays to zero backfilled rows`() {
        val dbFile = Files.createTempFile("chatbot-instructions-lenient", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            flywayFor(config.url, target = "31").migrate()

            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    // Unparseable text, a scalar, a JSON object and an empty array: all degrade to zero
                    // entries (the same fallback the role read applies) instead of failing the migration.
                    val values = listOf("'garbage'", "'42'", "'{\"a\":1}'", "'[]'")
                    values.forEachIndexed { index, stored ->
                        statement.executeUpdate(
                            "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                                "VALUES (${index + 1}, 'role$index', '', $stored, 0, 0)"
                        )
                    }
                }
            }

            flywayFor(config.url).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM instructions"))
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM agent_role_instructions"))
                assertFalse(hasColumn(connection, "agent_roles", "instructions_json"))
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `instruction link deletion rules on runtime connections`() {
        val dbFile = Files.createTempFile("chatbot-instructions-fk", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            // Runtime-style connection WITH FK enforcement, like the app's Exposed connections.
            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, created_at, updated_at) " +
                            "VALUES (2, 'reviewer', '', 0, 0)"
                    )
                    for (instructionId in 1L..2L) {
                        statement.executeUpdate(
                            "INSERT INTO instructions (id, type, name, message, created_at, updated_at) " +
                                "VALUES ($instructionId, 'custom', 'Tone', 'Be concise', 0, 0)"
                        )
                        statement.executeUpdate(
                            "INSERT INTO instruction_owners (instruction_id, user_id) VALUES ($instructionId, 1)"
                        )
                    }
                    // Role 1 lists both rows; role 2 shares the second one at position 3 (a gap left by
                    // a deletion is legal — relative order is what matters).
                    statement.executeUpdate(
                        "INSERT INTO agent_role_instructions (agent_role_id, instruction_id, sequence) VALUES (1, 1, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_instructions (agent_role_id, instruction_id, sequence) VALUES (1, 2, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_instructions (agent_role_id, instruction_id, sequence) VALUES (2, 2, 3)"
                    )

                    // Link uniqueness is enforced at the storage level (composite PK).
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_instructions (agent_role_id, instruction_id, sequence) " +
                                "VALUES (1, 2, 9)"
                        )
                    }

                    // Deleting a role removes only its link rows; instruction rows survive.
                    statement.executeUpdate("DELETE FROM agent_roles WHERE id = 1")
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM agent_role_instructions WHERE agent_role_id = 1"))
                    assertEquals(2, countRows(connection, "SELECT COUNT(*) FROM instructions"))

                    // The link constraint refuses a delete while a role still links the row, so both
                    // the row and its link survive.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("DELETE FROM instructions WHERE id = 2")
                    }
                    assertEquals(2, countRows(connection, "SELECT COUNT(*) FROM instructions"))
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_role_instructions"))

                    // Once the last link is gone the row is deletable, and its ownership row cascades
                    // with it.
                    statement.executeUpdate(
                        "DELETE FROM agent_role_instructions WHERE agent_role_id = 2 AND instruction_id = 2"
                    )
                    statement.executeUpdate("DELETE FROM instructions WHERE id = 2")
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM instructions"))
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM instruction_owners WHERE instruction_id = 2"))
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM instruction_owners"))
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `fresh database migration creates the instruction tables and discovers V32`() {
        val dbFile = Files.createTempFile("chatbot-instructions-fresh", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertTrue(hasTable(connection, "instructions"), "instructions table must exist")
                assertTrue(hasTable(connection, "agent_role_instructions"), "link table must exist")
                assertTrue(hasTable(connection, "instruction_owners"), "owners table must exist")
                assertTrue(
                    hasIndex(connection, "agent_role_instructions_instruction_idx"),
                    "reverse-lookup index must exist"
                )
                assertFalse(hasColumn(connection, "agent_roles", "instructions_json"))
                // On a fresh database the backfill has nothing to copy.
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM instructions"))
            }

            // The Java migration is discovered from the classpath location: Flyway must know V32.
            val versions = flywayFor(config.url).info().all().map { it.version.toString() }
            assertTrue("32" in versions, "Flyway must discover the V32 Java migration, got $versions")
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Builds a Flyway instance over the given SQLite URL, optionally stopping at [target].
     *
     * Mirrors [DatabaseMigrator] (same datasource style, same locations) so the migration behavior
     * under test matches production. Foreign key enforcement is left at the driver default (OFF),
     * which is what the SQL rebuild migrations (V10, V20) rely on. Java migrations are discovered
     * from the same classpath location as the SQL scripts.
     *
     * @param url The SQLite JDBC URL.
     * @param target Optional maximum schema version to migrate to.
     * @return A configured, not-yet-executed [Flyway] instance.
     */
    private fun flywayFor(url: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(url, "", "")
            .locations("classpath:db/migration")
            .baselineVersion("1")
            .cleanDisabled(true)
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    /**
     * Whether a table with the given name exists in the database.
     *
     * @param connection The open connection.
     * @param tableName The table name to look up.
     * @return `true` if the table exists, `false` otherwise.
     */
    private fun hasTable(connection: Connection, tableName: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    /**
     * Whether an index with the given name exists in the database.
     *
     * @param connection The open connection.
     * @param indexName The index name to look up.
     * @return `true` if the index exists, `false` otherwise.
     */
    private fun hasIndex(connection: Connection, indexName: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?").use { statement ->
            statement.setString(1, indexName)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    /**
     * Whether the given table has the given column.
     *
     * @param connection The open connection.
     * @param tableName The table to inspect.
     * @param columnName The column to look up.
     * @return `true` if the column exists, `false` otherwise.
     */
    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
                var found = false
                while (resultSet.next()) {
                    if (resultSet.getString("name") == columnName) found = true
                }
                found
            }
        }

    /**
     * Runs a count query and returns the single-row result.
     *
     * @param connection The open connection.
     * @param sql The count query.
     * @return The count value.
     */
    private fun countRows(connection: Connection, sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    /**
     * Runs a single-column text query and returns all values in result order.
     *
     * @param connection The open connection.
     * @param sql The query to run.
     * @return The column values.
     */
    private fun queryStrings(connection: Connection, sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.getString(1)) }
            }
        }

    /**
     * Runs a single-column text query and returns all values in result order, preserving SQL NULL.
     *
     * @param connection The open connection.
     * @param sql The query to run.
     * @return The column values; null elements are SQL NULLs.
     */
    private fun queryNullables(connection: Connection, sql: String): List<String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.getString(1)) }
            }
        }

    /**
     * Runs a single-column integer query and returns all values in result order.
     *
     * @param connection The open connection.
     * @param sql The query to run.
     * @return The column values.
     */
    private fun queryInts(connection: Connection, sql: String): List<Int> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.getInt(1)) }
            }
        }
}
