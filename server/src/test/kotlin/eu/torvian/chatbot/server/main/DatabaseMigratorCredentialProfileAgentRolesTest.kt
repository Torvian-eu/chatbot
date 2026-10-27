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
import kotlin.test.assertTrue

/**
 * Migration tests for the credential-profile ↔ agent-role link table introduced by V38.
 *
 * V38 only creates a table and an index, so an upgrade has no data migration and no rollback story
 * beyond "the table is left unused". These tests pin the composite key, both cascades, the index on
 * the role column and the deliberate absence of backfill rows.
 */
class DatabaseMigratorCredentialProfileAgentRolesTest {

    /**
     * Ensures a fresh database gains the link table with its columns and the role-column index.
     */
    @Test
    fun `latest migration creates the profile agent role link table`() {
        val dbFile = Files.createTempFile("chatbot-profile-roles-clean", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertTrue(hasTable(connection, "worker_credential_profile_agent_roles"))
                listOf("profile_id", "agent_role_id").forEach { column ->
                    assertTrue(
                        hasColumn(connection, "worker_credential_profile_agent_roles", column),
                        "worker_credential_profile_agent_roles.$column must exist"
                    )
                }
                assertTrue(
                    hasIndex(connection, "worker_credential_profile_agent_roles_agent_role_id_idx"),
                    "reverse lookups by agent role must be indexed"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Ensures V38 upgrades a populated V37 database without touching existing rows and inserts no
     * link rows, so every pre-existing profile stays a draft.
     */
    @Test
    fun `V38 upgrades a populated V37 database without backfilling links`() {
        val dbFile = Files.createTempFile("chatbot-profile-roles-upgrade", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            flywayFor(config.url, target = "37").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(USER_ROW)
                    statement.executeUpdate(WORKER_ROW)
                    statement.executeUpdate(AGENT_ROLE_ROW.format(1, "role-a"))
                    statement.executeUpdate(PROFILE_ROW)
                }
            }

            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM worker_credential_profiles"))
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM agent_roles"))
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM worker_credential_profile_agent_roles")
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Ensures the composite key rejects duplicates and both cascades remove link rows on a connection
     * that enforces foreign keys, like the runtime one.
     */
    @Test
    fun `link constraints reject duplicates and cascade from profiles and roles`() {
        val dbFile = Files.createTempFile("chatbot-profile-roles-fk", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(USER_ROW)
                    statement.executeUpdate(WORKER_ROW)
                    statement.executeUpdate(AGENT_ROLE_ROW.format(1, "role-a"))
                    statement.executeUpdate(AGENT_ROLE_ROW.format(2, "role-b"))
                    statement.executeUpdate(PROFILE_ROW)
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_profile_agent_roles (profile_id, agent_role_id) " +
                            "VALUES (1, 1)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_profile_agent_roles (profile_id, agent_role_id) " +
                            "VALUES (1, 2)"
                    )

                    // The composite primary key makes a duplicate link impossible.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO worker_credential_profile_agent_roles (profile_id, agent_role_id) " +
                                "VALUES (1, 1)"
                        )
                    }
                    // A link to a missing profile is rejected by the foreign key.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO worker_credential_profile_agent_roles (profile_id, agent_role_id) " +
                                "VALUES (99, 1)"
                        )
                    }

                    // Deleting a role drops only its own link, leaving the profile and its signature.
                    statement.executeUpdate("DELETE FROM agent_roles WHERE id = 2")
                    assertEquals(
                        1,
                        countRows(connection, "SELECT COUNT(*) FROM worker_credential_profile_agent_roles")
                    )
                    assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM worker_credential_profiles"))

                    // Deleting the profile drops the remaining link.
                    statement.executeUpdate("DELETE FROM worker_credential_profiles WHERE id = 1")
                    assertEquals(
                        0,
                        countRows(connection, "SELECT COUNT(*) FROM worker_credential_profile_agent_roles")
                    )
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * @param url SQLite JDBC URL of the test database.
     * @param target Flyway target version to stop at.
     * @return A Flyway instance bound to the migration classpath.
     */
    private fun flywayFor(url: String, target: String): Flyway = Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:db/migration")
        .target(target)
        .load()

    /**
     * @param connection Open connection.
     * @param table Table name without quotes.
     * @return Whether the table exists.
     */
    private fun hasTable(connection: Connection, table: String): Boolean =
        connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use { it.next() }

    /**
     * @param connection Open connection.
     * @param table Table name.
     * @param column Column name.
     * @return Whether the column exists.
     */
    private fun hasColumn(connection: Connection, table: String, column: String): Boolean =
        connection.metaData.getColumns(null, null, table, column).use { it.next() }

    /**
     * @param connection Open connection.
     * @param index Index name.
     * @return Whether an index with that name exists.
     */
    private fun hasIndex(connection: Connection, index: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$index'"
            ).use { it.next() }
        }

    /**
     * @param connection Open connection.
     * @param sql Single-column count query.
     * @return The count.
     */
    private fun countRows(connection: Connection, sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private companion object {
        /** Minimal user row the FK chain of workers and roles needs. */
        const val USER_ROW =
            "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"

        /** Minimal worker row owned by the user above. */
        const val WORKER_ROW =
            "INSERT INTO workers (id, owner_user_id, worker_uid, display_name, certificate_pem, " +
                "certificate_fingerprint, allowed_scopes_json, created_at) " +
                "VALUES (1, 1, 'uid-1', 'w', 'pem', 'fp', '[]', 0)"

        /** Agent-role template taking the role id and name. */
        const val AGENT_ROLE_ROW =
            "INSERT INTO agent_roles (id, name, display_name, description, created_at, updated_at) " +
                "VALUES (%d, '%s', NULL, '', 0, 0)"

        /** Minimal credential-profile row on the worker above. */
        const val PROFILE_ROW =
            "INSERT INTO worker_credential_profiles " +
                "(id, worker_id, name, secret_ref, command, channel, created_at, updated_at) " +
                "VALUES (1, 1, 'p', 'ref', '/bin/sh', 'ENV', 0, 0)"
    }
}
