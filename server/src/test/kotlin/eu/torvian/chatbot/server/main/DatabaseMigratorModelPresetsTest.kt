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
 * Migration regression tests for the model-presets schema changes (V29).
 *
 * V29 introduces the `model_presets` + `model_preset_owners` tables and rebuilds `agent_roles` so that
 * it keeps exactly one configuration reference (`model_preset_id`, `ON DELETE SET NULL`) and loses
 * `model_id`/`model_settings_id` plus `agent_roles_model_settings_id_idx`. The rebuild is the standard
 * create-copy-drop-rename procedure because SQLite cannot `DROP COLUMN` an FK-constrained/indexed
 * column, and it relies on migration connections running with foreign-key enforcement OFF (otherwise
 * `DROP TABLE agent_roles` would cascade into the six child tables).
 *
 * The migration is also deliberately **lossy**: no preset is generated from existing role data, so
 * every pre-existing role ends up preset-less and non-sendable, and the discarded
 * `(model_id, model_settings_id)` values are unrecoverable. These tests pin the resulting schema, the
 * absence of row loss and the runtime `SET NULL` semantics.
 */
class DatabaseMigratorModelPresetsTest {

    @Test
    fun `latest migration applies clean and creates the model-preset schema`() {
        val dbFile = Files.createTempFile("chatbot-model-presets-clean", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertTrue(hasTable(connection, "model_presets"), "model_presets table must exist")
                assertTrue(hasTable(connection, "model_preset_owners"), "model_preset_owners table must exist")

                // model_presets: both reference columns plus the timestamps and the three indexes.
                assertTrue(hasColumn(connection, "model_presets", "model_id"), "model_presets.model_id must exist")
                assertTrue(
                    hasColumn(connection, "model_presets", "model_settings_id"),
                    "model_presets.model_settings_id must exist"
                )
                assertTrue(hasColumn(connection, "model_presets", "created_at"), "model_presets.created_at must exist")
                assertTrue(hasColumn(connection, "model_presets", "updated_at"), "model_presets.updated_at must exist")
                assertTrue(hasIndex(connection, "model_presets_name_idx"), "model_presets_name_idx must exist")
                assertTrue(
                    hasIndex(connection, "model_presets_model_id_idx"),
                    "model_presets_model_id_idx must exist"
                )
                assertTrue(
                    hasIndex(connection, "model_presets_model_settings_id_idx"),
                    "model_presets_model_settings_id_idx must exist"
                )

                // model_preset_owners: exactly one owner per preset (preset_id is the primary key).
                assertEquals(
                    listOf("preset_id"),
                    primaryKeyColumns(connection, "model_preset_owners"),
                    "preset_id must be the model_preset_owners PK"
                )

                // agent_roles: only the single preset reference survives, with the preset index.
                assertTrue(
                    hasColumn(connection, "agent_roles", "model_preset_id"),
                    "agent_roles.model_preset_id must exist"
                )
                assertFalse(hasColumn(connection, "agent_roles", "model_id"), "agent_roles.model_id must be gone")
                assertFalse(
                    hasColumn(connection, "agent_roles", "model_settings_id"),
                    "agent_roles.model_settings_id must be gone"
                )
                assertTrue(
                    hasIndex(connection, "agent_roles_model_preset_id_idx"),
                    "agent_roles_model_preset_id_idx must exist"
                )
                assertFalse(
                    hasIndex(connection, "agent_roles_model_settings_id_idx"),
                    "the legacy agent_roles_model_settings_id_idx must not be recreated"
                )
                // The surviving indexes are recreated by the rebuild.
                assertTrue(hasIndex(connection, "agent_roles_name_idx"), "agent_roles_name_idx must survive")
                assertTrue(
                    hasIndex(connection, "agent_roles_project_id_idx"),
                    "agent_roles_project_id_idx must survive"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V29 upgrades a populated V28 database without losing rows and drops the legacy configuration`() {
        val dbFile = Files.createTempFile("chatbot-model-presets-upgrade", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())

            // Migrate to V28 (the state right before model presets) and seed a fully populated role set:
            // two roles with legacy model/settings configuration and a project, plus one row in every
            // table that references agent_roles.
            flywayFor(config.url, target = "28").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (2, 'u2', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO llm_providers (id, api_key_id, name, description, base_url, type) " +
                            "VALUES (1, NULL, 'OpenAI', '', 'https://api.openai.com/v1', 'OPENAI')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO llm_models (id, name, provider_id, active, display_name) " +
                            "VALUES (1, 'gpt-4', 1, 1, 'GPT-4')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO model_settings (id, model_id, name, type, variable_params_json, custom_params_json) " +
                            "VALUES (1, 1, 'Default', 'CHAT', '{}', NULL)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO projects (id, name, description, created_at, updated_at) VALUES (1, 'Acme', '', 0, 0)"
                    )
                    // Two roles with the legacy configuration set (the values V29 discards).
                    statement.executeUpdate(
                        "INSERT INTO agent_roles " +
                            "(id, name, description, model_id, model_settings_id, instructions_json, created_at, updated_at, project_id) " +
                            "VALUES (1, 'architect', '', 1, 1, '[]', 0, 0, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles " +
                            "(id, name, description, model_id, model_settings_id, instructions_json, created_at, updated_at, project_id) " +
                            "VALUES (2, 'reviewer', '', 1, 1, '[]', 0, 0, NULL)"
                    )
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (2, 2)")
                    statement.executeUpdate(
                        "INSERT INTO tool_definitions " +
                            "(id, name, description, type, config_json, input_schema_json, is_enabled, created_at, updated_at) " +
                            "VALUES (1, 'tool', '', 'BUILTIN', '{}', '{}', 1, 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO agent_role_tools (role_id, tool_definition_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO agent_role_spawnable_roles (source_role_id, target_role_id) VALUES (1, 2)"
                    )
                    statement.executeUpdate("INSERT INTO agent_role_disabled (role_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO chat_sessions (id, name, created_at, updated_at, group_id, agent_role_id) " +
                            "VALUES (1, 's1', 0, 0, NULL, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO chat_messages " +
                            "(id, session_id, role, content, created_at, updated_at, parent_message_id, children_message_ids) " +
                            "VALUES (1, 1, 'ASSISTANT', 'hi', 0, 0, NULL, '[]')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO assistant_messages (message_id, model_id, settings_id, agent_role_id) " +
                            "VALUES (1, 1, 1, 1)"
                    )
                }
            }

            // Migrate to latest (V29).
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                // Schema: legacy columns/index gone, the single reference present.
                assertFalse(hasColumn(connection, "agent_roles", "model_id"))
                assertFalse(hasColumn(connection, "agent_roles", "model_settings_id"))
                assertFalse(hasIndex(connection, "agent_roles_model_settings_id_idx"))
                assertTrue(hasColumn(connection, "agent_roles", "model_preset_id"))

                // Deliberate non-migration: no presets are generated and every pre-existing role is
                // preset-less (its old configuration was discarded, which is why it is non-sendable).
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM model_presets"))
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM model_preset_owners"))
                assertEquals(
                    2,
                    countRows(connection, "SELECT COUNT(*) FROM agent_roles WHERE model_preset_id IS NULL"),
                    "every pre-existing role must end up preset-less"
                )

                // No row loss anywhere: agent_roles and all six referencing tables are intact, with the
                // same primary keys as before the rebuild.
                assertEquals(listOf(1L, 2L), keyValues(connection, "agent_roles"))
                assertEquals(listOf(1L, 2L), keyValues(connection, "agent_role_owners", "role_id"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_role_tools"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_role_spawnable_roles"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_role_disabled"))
                assertEquals(listOf(1L), keyValues(connection, "chat_sessions"))
                assertEquals(listOf(1L), keyValues(connection, "chat_messages"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM assistant_messages"))
                // The child FOREIGN KEY clauses still resolve to the renamed table.
                assertEquals(
                    1L,
                    queryNullableLong(connection, "SELECT agent_role_id FROM chat_sessions WHERE id = 1"),
                    "chat_sessions.agent_role_id must survive the rebuild"
                )
                assertEquals(
                    1L,
                    queryNullableLong(connection, "SELECT agent_role_id FROM assistant_messages WHERE message_id = 1"),
                    "assistant_messages.agent_role_id must survive the rebuild"
                )
                // The preserved project membership is carried over verbatim.
                assertEquals(
                    1L,
                    queryNullableLong(connection, "SELECT project_id FROM agent_roles WHERE id = 1")
                )

                // Referential integrity is intact after the rebuild.
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_check").use { resultSet ->
                        assertFalse(resultSet.next(), "foreign_key_check must be clean after the rebuild")
                    }
                }

                // The AUTOINCREMENT sequence continues above the previous maximum: an explicit-id copy
                // updates sqlite_sequence, so the next role gets id 3 rather than reusing a freed id.
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (name, description, instructions_json, created_at, updated_at) " +
                            "VALUES ('new-role', '', '[]', 0, 0)"
                    )
                    statement.executeQuery("SELECT MAX(id) FROM agent_roles").use { resultSet ->
                        resultSet.next()
                        assertEquals(3L, resultSet.getLong(1), "ids must continue above the previous maximum")
                    }
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `model preset constraints enforce SET NULL semantics on runtime connections`() {
        val dbFile = Files.createTempFile("chatbot-model-presets-fk", ".db")
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
                        "INSERT INTO llm_providers (id, api_key_id, name, description, base_url, type) " +
                            "VALUES (1, NULL, 'OpenAI', '', 'https://api.openai.com/v1', 'OPENAI')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO llm_models (id, name, provider_id, active, display_name) " +
                            "VALUES (1, 'gpt-4', 1, 1, 'GPT-4')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO model_settings (id, model_id, name, type, variable_params_json, custom_params_json) " +
                            "VALUES (1, 1, 'Default', 'CHAT', '{}', NULL)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO model_presets (id, name, description, model_id, model_settings_id, created_at, updated_at) " +
                            "VALUES (1, 'smart_model', '', 1, 1, 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO model_preset_owners (preset_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO agent_roles " +
                            "(id, name, description, model_preset_id, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', 1, '[]', 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (1, 1)")

                    // Unknown references are rejected by the foreign keys.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("UPDATE agent_roles SET model_preset_id = 999 WHERE id = 1")
                    }
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("UPDATE model_presets SET model_settings_id = 999 WHERE id = 1")
                    }
                    // Exactly one owner per preset (preset_id is the PK).
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("INSERT INTO model_preset_owners (preset_id, user_id) VALUES (1, 1)")
                    }

                    // Deleting the preset nulls the role's reference: the role survives and becomes
                    // non-sendable, and nothing else about it is touched (no CASCADE anywhere).
                    statement.executeUpdate("DELETE FROM model_presets WHERE id = 1")
                    assertNull(
                        queryNullableLong(connection, "SELECT model_preset_id FROM agent_roles WHERE id = 1"),
                        "deleting a preset must null the role's reference"
                    )
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_roles"))
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_role_owners"))
                    // The owner row of the preset cascades away (it references the deleted preset).
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM model_preset_owners"))

                    // Deleting a settings profile nulls the preset's settings reference without
                    // deleting the preset itself.
                    statement.executeUpdate(
                        "INSERT INTO model_presets (id, name, description, model_id, model_settings_id, created_at, updated_at) " +
                            "VALUES (2, 'cheap_model', '', 1, 1, 0, 0)"
                    )
                    statement.executeUpdate("DELETE FROM model_settings WHERE id = 1")
                    assertNull(
                        queryNullableLong(connection, "SELECT model_settings_id FROM model_presets WHERE id = 2"),
                        "deleting settings must null the preset's settings reference"
                    )
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM model_presets"))

                    // Deleting a model nulls the preset's model reference too.
                    statement.executeUpdate(
                        "INSERT INTO model_settings (id, model_id, name, type, variable_params_json, custom_params_json) " +
                            "VALUES (2, 1, 'Default 2', 'CHAT', '{}', NULL)"
                    )
                    statement.executeUpdate("UPDATE model_presets SET model_id = 1, model_settings_id = 2 WHERE id = 2")
                    statement.executeUpdate("DELETE FROM llm_models WHERE id = 1")
                    assertNull(
                        queryNullableLong(connection, "SELECT model_id FROM model_presets WHERE id = 2"),
                        "deleting a model must null the preset's model reference"
                    )
                    // model_settings.model_id is ON DELETE CASCADE, so the settings rows vanish with the
                    // model and the preset's settings reference is nulled as well (D-2).
                    assertNull(
                        queryNullableLong(connection, "SELECT model_settings_id FROM model_presets WHERE id = 2"),
                        "the settings cascade nulls the preset's settings reference as well"
                    )

                    statement.executeQuery("PRAGMA foreign_key_check").use { resultSet ->
                        assertFalse(resultSet.next(), "foreign_key_check must be clean after the cascades")
                    }
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Builds a Flyway instance over the given SQLite URL, optionally stopping at [target].
     *
     * Mirrors [DatabaseMigrator] (same datasource style, same locations) so the migration behavior
     * under test matches production exactly.
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
     * Whether a table has a column with the given name.
     *
     * @param connection The open connection.
     * @param tableName The table to inspect.
     * @param columnName The column to look up.
     * @return `true` if the column exists, `false` otherwise.
     */
    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    if (resultSet.getString("name") == columnName) return true
                }
                false
            }
        }

    /**
     * Returns the primary-key column names of a table in declaration order.
     *
     * @param connection The open connection.
     * @param tableName The table to inspect.
     * @return The PK column names; empty if the table has no PK.
     */
    private fun primaryKeyColumns(connection: Connection, tableName: String): List<String> =
        connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
            statement.executeQuery().use { resultSet ->
                val pkColumns = mutableListOf<Pair<Int, String>>()
                while (resultSet.next()) {
                    val pk = resultSet.getInt("pk")
                    if (pk > 0) pkColumns += pk to resultSet.getString("name")
                }
                pkColumns.sortedBy { it.first }.map { it.second }
            }
        }

    /**
     * Returns one column's values of a table ordered ascending; used to compare the primary-key sets
     * before and after the rebuild.
     *
     * @param connection The open connection.
     * @param tableName The table to read.
     * @param columnName The column to project (the table's key column).
     * @return The ordered value list.
     */
    private fun keyValues(connection: Connection, tableName: String, columnName: String = "id"): List<Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $columnName FROM $tableName ORDER BY $columnName").use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getLong(1))
                }
            }
        }

    /**
     * Runs [sql] and returns the first column of the first row as an `Int`.
     *
     * @param connection The open connection.
     * @param sql The counting query.
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
     * Runs [sql] and returns the first column of the first row as a nullable `Long`.
     *
     * @param connection The open connection.
     * @param sql The scalar query.
     * @return The value, or `null` if the SQL column is NULL.
     */
    private fun queryNullableLong(connection: Connection, sql: String): Long? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getLong(1).let { if (resultSet.wasNull()) null else it }
            }
        }
}
