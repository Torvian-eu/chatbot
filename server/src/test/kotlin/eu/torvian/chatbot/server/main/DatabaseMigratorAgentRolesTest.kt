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
 * Migration regression tests for the agent-role schema changes (V19/V20).
 *
 * These tests exercise the two review findings on real SQLite databases created from the migration
 * scripts:
 *
 *  1. `agent_roles.name` must NOT be globally unique — different users must be able to reuse the same
 *     role name (uniqueness is scoped per user and enforced at the service layer).
 *  2. V20's `chat_sessions` rebuild (DROP + rename) must not cascade-delete child rows
 *     (`chat_session_owners`, `session_tool_config`, `session_current_leaf`, `chat_messages`) — this
 *     holds because migration connections open with foreign key enforcement OFF by default (the xerial
 *     driver default, same as V10's existing rebuild), and the rebuilt table keeps working for foreign
 *     key references (verified by `rebuild keeps foreign key references working against the rebuilt
 *     table`).
 */
class DatabaseMigratorAgentRolesTest {

    @Test
    fun `agent role names can be reused across users`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-name", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertFalse(hasIndex(connection, "agent_roles_name_unique"), "global unique name index must not exist")
                assertTrue(hasIndex(connection, "agent_roles_name_idx"), "plain name index should exist")

                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (2, 'u2', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    // Two users, same machine-readable name — must both be accepted.
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (2, 'architect', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate("INSERT INTO agent_role_owners (role_id, user_id) VALUES (2, 2)")
                }

                assertEquals(
                    2,
                    countRows(connection, "SELECT COUNT(*) FROM agent_roles WHERE name = 'architect'"),
                    "both users' roles with the same name must persist"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `agent role tools are normalized with referential integrity and cascades`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-tools", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    // agent_roles no longer stores tools as a JSON column; the role-tool relation lives
                    // in the agent_role_tools join table.
                    statement.executeQuery("PRAGMA table_info(agent_roles)").use { resultSet ->
                        var hasToolsJson = false
                        while (resultSet.next()) {
                            if (resultSet.getString("name") == "tools_json") hasToolsJson = true
                        }
                        assertFalse(hasToolsJson, "tools_json must not exist on agent_roles")
                    }

                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO tool_definitions (id, name, description, type, config_json, input_schema_json, is_enabled, created_at, updated_at) " +
                            "VALUES (1, 't1', 'd', 'mcp', '{}', '{}', 1, 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_tools (role_id, tool_definition_id) VALUES (1, 1)"
                    )
                }
            }

            // Runtime-style connection WITH FK enforcement, like the app's Exposed connections.
            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                connection.createStatement().use { statement ->
                    // Duplicate (role, tool) pairs are rejected by the primary key.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_tools (role_id, tool_definition_id) VALUES (1, 1)"
                        )
                    }

                    // A link to an unknown tool definition is rejected by the foreign key.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_tools (role_id, tool_definition_id) VALUES (1, 999)"
                        )
                    }
                }

                // Deleting the tool definition cascades away the role-tool rows (no dangling ids).
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM tool_definitions WHERE id = 1")
                }
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_tools"),
                    "tool deletion must cascade away role-tool rows"
                )
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_check").use { resultSet ->
                        assertFalse(resultSet.next(), "foreign_key_check must be clean after the cascade")
                    }
                }

                // Deleting the role cascades its tool rows too.
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (2, 'architect2', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO tool_definitions (id, name, description, type, config_json, input_schema_json, is_enabled, created_at, updated_at) " +
                            "VALUES (2, 't2', 'd', 'mcp', '{}', '{}', 1, 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_tools (role_id, tool_definition_id) VALUES (2, 2)"
                    )
                    statement.executeUpdate("DELETE FROM agent_roles WHERE id = 2")
                }
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_tools WHERE role_id = 2"),
                    "role deletion must cascade away its tool rows"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `chat sessions rebuild preserves child rows`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-rebuild", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())

            // Migrate to V18 (the state right before agent roles) and seed a session with all children.
            flywayFor(config.url, target = "18").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO chat_sessions (id, name, created_at, updated_at) VALUES (1, 's1', 0, 0)")
                    statement.executeUpdate("INSERT INTO chat_session_owners (session_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO chat_messages (id, session_id, role, content, created_at, updated_at, children_message_ids) " +
                            "VALUES (1, 1, 'user', 'hello', 0, 0, '[]')"
                    )
                    statement.executeUpdate("INSERT INTO session_current_leaf (session_id, message_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO tool_definitions (id, name, description, type, config_json, input_schema_json, is_enabled, created_at, updated_at) " +
                            "VALUES (1, 't1', 'd', 'mcp', '{}', '{}', 1, 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO session_tool_config (session_id, tool_definition_id, is_enabled) VALUES (1, 1, 1)")
                }
            }

            // Migrate to latest (V19 creates agent_roles, V20 rebuilds chat_sessions).
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                // None of the child tables may have lost rows to a cascade triggered by DROP TABLE.
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM chat_session_owners"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM session_tool_config"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM session_current_leaf"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM chat_messages"))
                // The session row survived the rebuild and is unassigned to any agent role.
                assertNull(queryNullableLong(connection, "SELECT agent_role_id FROM chat_sessions WHERE id = 1"))
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `rebuild keeps foreign key references working against the rebuilt table`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-fk", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())

            // Migrate to V18 and seed a session with children (rows exist BEFORE the rebuild).
            flywayFor(config.url, target = "18").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO chat_sessions (id, name, created_at, updated_at) VALUES (1, 's1', 0, 0)")
                    statement.executeUpdate(
                        "INSERT INTO chat_messages (id, session_id, role, content, created_at, updated_at, children_message_ids) " +
                            "VALUES (1, 1, 'user', 'hello', 0, 0, '[]')"
                    )
                    statement.executeUpdate("INSERT INTO chat_session_owners (session_id, user_id) VALUES (1, 1)")
                    statement.executeUpdate("INSERT INTO session_current_leaf (session_id, message_id) VALUES (1, 1)")
                    statement.executeUpdate(
                        "INSERT INTO tool_definitions (id, name, description, type, config_json, input_schema_json, is_enabled, created_at, updated_at) " +
                            "VALUES (1, 't1', 'd', 'mcp', '{}', '{}', 1, 0, 0)"
                    )
                    statement.executeUpdate("INSERT INTO session_tool_config (session_id, tool_definition_id, is_enabled) VALUES (1, 1, 1)")
                }
            }

            // Migrate to latest (V20 rebuilds chat_sessions under the hood).
            DatabaseMigrator(config).migrate()

            // Runtime-style connection WITH FK enforcement, like the app's Exposed connections.
            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                // 1) Pre-existing child rows must satisfy every FK constraint against the REBUILT table.
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_check").use { resultSet ->
                        assertFalse(resultSet.next(), "foreign_key_check must be clean after the rebuild")
                    }
                }

                // 2) FKs are name-based: the child DDL still literally references "chat_sessions".
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='chat_messages'").use { resultSet ->
                        assertTrue(resultSet.next())
                        assertTrue(
                            resultSet.getString(1).contains("REFERENCES chat_sessions"),
                            "child FK must reference chat_sessions by name"
                        )
                    }
                }

                // 3) A valid child insert resolves against the rebuilt table.
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO chat_messages (id, session_id, role, content, created_at, updated_at, children_message_ids) " +
                            "VALUES (2, 1, 'assistant', 'hi', 0, 0, '[]')"
                    )
                }

                // 4) An insert referencing a missing session is rejected against the rebuilt table.
                connection.createStatement().use { statement ->
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO chat_messages (id, session_id, role, content, created_at, updated_at, children_message_ids) " +
                                "VALUES (3, 999, 'user', 'x', 0, 0, '[]')"
                        )
                    }
                }

                // 5) ON DELETE CASCADE still works against the rebuilt table.
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM chat_sessions WHERE id = 1")
                }
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM chat_messages"),
                    "messages must cascade when the rebuilt session is deleted"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `agent role disabled rows are per-user side-table state`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-disabled", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    // `agent_role_disabled` must exist and `agent_roles` must NOT gain a `disabled`
                    // column (the disabled state is a per-user side-table marker, not a role row field).
                    assertTrue(hasTable(connection, "agent_role_disabled"))
                    statement.executeQuery("PRAGMA table_info(agent_roles)").use { resultSet ->
                        var hasDisabledColumn = false
                        while (resultSet.next()) {
                            if (resultSet.getString("name") == "disabled") hasDisabledColumn = true
                        }
                        assertFalse(hasDisabledColumn, "disabled column must not exist on agent_roles")
                    }

                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (2, 'u2', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (1, 1)"
                    )
                    // Per-user rows for the SAME role coexist: (role 1, userB) is accepted next to (role 1, userA).
                    statement.executeUpdate(
                        "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (1, 2)"
                    )
                }
            }

            // Runtime-style connection WITH FK enforcement, like the app's Exposed connections.
            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                connection.createStatement().use { statement ->
                    // Duplicate (role, user) rows are rejected by the composite primary key.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (1, 1)"
                        )
                    }

                    // Unknown role/user references are rejected by the foreign keys.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (999, 1)"
                        )
                    }
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (1, 999)"
                        )
                    }
                }

                // A second role plus a disabled row for it: deleting the role cascades every user's rows.
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (2, 'reviewer', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (2, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_disabled (role_id, user_id) VALUES (2, 2)"
                    )
                    statement.executeUpdate("DELETE FROM agent_roles WHERE id = 2")
                }
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_disabled WHERE role_id = 2"),
                    "role deletion must cascade away every user's disabled rows for that role"
                )

                // Deleting a user cascades that user's rows only (role 1 still has user 2's row).
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM users WHERE id = 1")
                }
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_disabled WHERE user_id = 1"),
                    "user deletion must cascade away that user's disabled rows"
                )
                assertEquals(
                    1,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_disabled WHERE user_id = 2"),
                    "other users' disabled rows must survive a user deletion"
                )
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_check").use { resultSet ->
                        assertFalse(resultSet.next(), "foreign_key_check must be clean after the cascades")
                    }
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `agent roles seeded before V27 are all enabled for all users`() {
        val dbFile = Files.createTempFile("chatbot-agent-roles-disabled-upgrade", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())

            // Migrate to V26 (the state right before the disabled side table) and seed roles.
            flywayFor(config.url, target = "26").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at) " +
                            "VALUES (1, 'architect', '', '[]', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_role_owners (role_id, user_id) VALUES (1, 1)"
                    )
                }
            }

            // Migrate to latest (V27 creates agent_role_disabled without inserting rows).
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM agent_role_disabled"),
                    "upgrading a DB with existing roles must leave every role enabled for every user"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Builds a Flyway instance over the given SQLite URL, optionally stopping at [target].
     *
     * Mirrors [DatabaseMigrator] (same datasource style, same locations) so the migration behavior
     * under test matches production. Foreign key enforcement is left at the driver default (OFF),
     * which is what the SQL rebuild migrations (V10, V20) rely on.
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
