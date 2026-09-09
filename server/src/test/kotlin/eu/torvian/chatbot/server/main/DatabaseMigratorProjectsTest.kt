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
 * Migration regression tests for the projects feature schema changes (V28).
 *
 * These tests exercise the migration script on real SQLite databases, covering both the clean-apply
 * case and the upgrade of an existing V27 database:
 *
 * V28 creates `projects`, `project_owners`, the single nullable `agent_roles.project_id` membership
 * column (no join table) and the nullable `chat_sessions.project_id` column + indexes; on upgrade,
 * existing rows simply get NULL project ids (no data migration). Runtime-style connections (FK
 * enforcement ON, as used by Exposed in the app) enforce the single-owner PK, reject unknown
 * references, and apply the right ON DELETE semantics: deleting a project nulls the session's and
 * the roles' project ids while keeping sessions and roles intact; deleting a role removes its row;
 * deleting a user clears its owner rows.
 */
class DatabaseMigratorProjectsTest {

    @Test
    fun `latest migration applies clean and creates the project schema`() {
        val dbFile = Files.createTempFile("chatbot-projects-clean", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertTrue(hasTable(connection, "projects"), "projects table must exist")
                assertTrue(hasTable(connection, "project_owners"), "project_owners table must exist")
                // V28 never creates a join table: membership is the single nullable agent_roles.project_id.
                assertFalse(hasTable(connection, "project_agent_roles"), "project_agent_roles must not exist")
                assertTrue(hasIndex(connection, "projects_name_idx"), "projects_name_idx must exist")
                assertTrue(
                    hasIndex(connection, "agent_roles_project_id_idx"),
                    "agent_roles_project_id_idx must exist"
                )
                assertTrue(
                    hasIndex(connection, "chat_sessions_project_id_idx"),
                    "chat_sessions_project_id_idx must exist"
                )

                // chat_sessions carries the nullable project_id column (the migration adds it).
                assertTrue(hasColumn(connection, "chat_sessions", "project_id"), "project_id column must exist")
                // agent_roles carries the single nullable membership column.
                assertTrue(hasColumn(connection, "agent_roles", "project_id"), "agent_roles.project_id must exist")

                // project_owners: exactly one owner per project (project_id is the primary key).
                val ownerPk = primaryKeyColumns(connection, "project_owners")
                assertEquals(listOf("project_id"), ownerPk, "project_id must be the project_owners PK")
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `V28 upgrades an existing V27 database with project_id null and no data loss`() {
        val dbFile = Files.createTempFile("chatbot-projects-upgrade", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())

            // Migrate to V27 (the state right before projects) and seed a user, role and session.
            flywayFor(config.url, target = "27").migrate()
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
                    statement.executeUpdate(
                        "INSERT INTO chat_sessions (id, name, created_at, updated_at) VALUES (1, 's1', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO chat_session_owners (session_id, user_id) VALUES (1, 1)"
                    )
                }
            }

            // Migrate to latest (V28 adds the additive project schema).
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                // No data migration: existing sessions are project-less and existing roles stay
                // unassociated after the upgrade (the new column is NULL for every pre-V28 row).
                assertNull(
                    queryNullableLong(connection, "SELECT project_id FROM chat_sessions WHERE id = 1"),
                    "existing sessions must be project-less after upgrade"
                )
                assertNull(
                    queryNullableLong(connection, "SELECT project_id FROM agent_roles WHERE id = 1"),
                    "existing roles must stay unassociated after upgrade"
                )
                assertEquals(
                    1,
                    countRows(connection, "SELECT COUNT(*) FROM chat_session_owners"),
                    "existing session ownership must survive the upgrade"
                )
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM projects"))
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    @Test
    fun `project constraints enforce single ownership and FK behaviors on runtime connections`() {
        val dbFile = Files.createTempFile("chatbot-projects-fk", ".db")
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
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (2, 'u2', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    // Membership is the single nullable agent_roles.project_id column (V28); the
                    // referenced project must exist first (FK enforcement is ON here).
                    statement.executeUpdate(
                        "INSERT INTO projects (id, name, description, created_at, updated_at) " +
                            "VALUES (1, 'Acme', '', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO agent_roles (id, name, description, instructions_json, created_at, updated_at, project_id) " +
                            "VALUES (1, 'architect', '', '[]', 0, 0, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO project_owners (project_id, user_id) VALUES (1, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO chat_sessions (id, name, created_at, updated_at, project_id) " +
                            "VALUES (1, 's1', 0, 0, 1)"
                    )

                    // Exactly one owner: a second owner row is rejected by the project_id PK.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO project_owners (project_id, user_id) VALUES (1, 2)"
                        )
                    }

                    // Unknown references are rejected by the foreign keys.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("INSERT INTO project_owners (project_id, user_id) VALUES (999, 1)")
                    }
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("UPDATE agent_roles SET project_id = 999 WHERE id = 1")
                    }

                    // Deleting the project SET NULLs the session's project_id AND the role's
                    // membership column (sessions and roles survive).
                    statement.executeUpdate("DELETE FROM projects WHERE id = 1")
                    assertNull(
                        queryNullableLong(connection, "SELECT project_id FROM chat_sessions WHERE id = 1"),
                        "session project_id must be nulled by ON DELETE SET NULL"
                    )
                    assertNull(
                        queryNullableLong(connection, "SELECT project_id FROM agent_roles WHERE id = 1"),
                        "role project_id must be nulled by ON DELETE SET NULL"
                    )
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM chat_sessions"))
                    // The owner row cascades; the role itself survives.
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM project_owners"))
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_roles"))

                    // Deleting a role removes the role row (and its membership) but not projects.
                    statement.executeUpdate(
                        "INSERT INTO projects (id, name, description, created_at, updated_at) " +
                            "VALUES (2, 'Acme2', '', 0, 0)"
                    )
                    statement.executeUpdate("UPDATE agent_roles SET project_id = 2 WHERE id = 1")
                    statement.executeUpdate("DELETE FROM agent_roles WHERE id = 1")
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM agent_roles"))
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM projects"))

                    // Deleting a user cascades that user's owner rows.
                    statement.executeUpdate(
                        "INSERT INTO project_owners (project_id, user_id) VALUES (2, 1)"
                    )
                    statement.executeUpdate("DELETE FROM users WHERE id = 1")
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM project_owners WHERE user_id = 1"))

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