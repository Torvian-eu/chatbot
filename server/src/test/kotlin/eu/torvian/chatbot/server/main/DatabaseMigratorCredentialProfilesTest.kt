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
 * Migration tests for the credential-profile schema introduced by V37.
 *
 * V37 only adds tables, so an upgrade has no data migration and no rollback story beyond "the tables
 * are left unused". These tests pin the schema, the uniqueness constraints, the cascade behaviour and
 * the deliberate absence of a foreign key from a profile's `secret_ref`.
 */
class DatabaseMigratorCredentialProfilesTest {

    /**
     * Ensures a fresh database gains the three tables, their columns and their unique indexes.
     */
    @Test
    fun `latest migration creates the credential schema`() {
        val dbFile = Files.createTempFile("chatbot-credentials-clean", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertTrue(hasTable(connection, "worker_credential_profiles"))
                assertTrue(hasTable(connection, "worker_credential_secrets"))
                assertTrue(hasTable(connection, "worker_credential_profile_signatures"))

                listOf(
                    "worker_id", "name", "description", "secret_ref", "command", "args_json", "channel",
                    "env_name", "file_name", "askpass_username", "environment_json",
                    "askpass_variables_json", "is_enabled", "created_at", "updated_at"
                ).forEach { column ->
                    assertTrue(
                        hasColumn(connection, "worker_credential_profiles", column),
                        "worker_credential_profiles.$column must exist"
                    )
                }

                listOf(
                    "worker_id", "reference", "encrypted_secret", "encrypted_dek", "algorithm",
                    "worker_certificate_fingerprint", "created_at", "updated_at"
                ).forEach { column ->
                    assertTrue(
                        hasColumn(connection, "worker_credential_secrets", column),
                        "worker_credential_secrets.$column must exist"
                    )
                }

                listOf(
                    "profile_id", "user_device_id", "signature", "timestamp", "nonce", "payload_json"
                ).forEach { column ->
                    assertTrue(
                        hasColumn(connection, "worker_credential_profile_signatures", column),
                        "worker_credential_profile_signatures.$column must exist"
                    )
                }

                assertTrue(
                    hasIndex(connection, "worker_credential_profiles_worker_id_name_unique"),
                    "profiles must be unique per (worker, name)"
                )
                assertTrue(
                    hasIndex(connection, "worker_credential_secrets_worker_id_reference_unique"),
                    "secrets must be unique per (worker, reference)"
                )
                assertTrue(
                    hasIndex(connection, "worker_credential_profile_signatures_profile_id_user_device_id_unique"),
                    "signatures must be unique per (profile, device)"
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Ensures V37 leaves an upgraded, populated database intact and starts empty.
     */
    @Test
    fun `V37 upgrades a populated V36 database without touching existing rows`() {
        val dbFile = Files.createTempFile("chatbot-credentials-upgrade", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            flywayFor(config.url, target = "36").migrate()
            DriverManager.getConnection(config.url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO workers (id, owner_user_id, worker_uid, display_name, certificate_pem, " +
                            "certificate_fingerprint, allowed_scopes_json, created_at) " +
                            "VALUES (1, 1, 'uid-1', 'w', 'pem', 'fp', '[]', 0)"
                    )
                }
            }

            DatabaseMigrator(config).migrate()

            DriverManager.getConnection(config.url).use { connection ->
                assertEquals(1, countRows(connection, "SELECT COUNT(*) FROM workers"))
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM worker_credential_profiles"))
                assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM worker_credential_secrets"))
                assertEquals(
                    0,
                    countRows(connection, "SELECT COUNT(*) FROM worker_credential_profile_signatures")
                )
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    /**
     * Ensures uniqueness, the worker cascade and the absence of a profile-to-secret foreign key behave
     * as documented on a connection that enforces foreign keys, like the runtime one.
     */
    @Test
    fun `credential constraints enforce uniqueness and cascade without linking profiles to secrets`() {
        val dbFile = Files.createTempFile("chatbot-credentials-fk", ".db")
        try {
            val config = DatabaseConfig(vendor = "sqlite", type = "file", filepath = dbFile.toString())
            DatabaseMigrator(config).migrate()

            val runtimeConfig = SQLiteConfig().apply { enforceForeignKeys(true) }
            SQLiteDataSource(runtimeConfig).apply { url = config.url }.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO users (id, username, password_hash, email, status, created_at, updated_at) " +
                            "VALUES (1, 'u1', 'h', NULL, 'ENABLED', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO workers (id, owner_user_id, worker_uid, display_name, certificate_pem, " +
                            "certificate_fingerprint, allowed_scopes_json, created_at) " +
                            "VALUES (1, 1, 'uid-1', 'w', 'pem', 'fp', '[]', 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO user_devices (id, user_id, client_device_id, created_at, last_used_at) " +
                            "VALUES (1, 1, 'device-1', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_secrets " +
                            "(id, worker_id, reference, encrypted_secret, encrypted_dek, algorithm, " +
                            "worker_certificate_fingerprint, created_at, updated_at) " +
                            "VALUES (1, 1, 'ref', 'cipher', 'dek', 'alg', 'fp', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_profiles " +
                            "(id, worker_id, name, secret_ref, command, channel, created_at, updated_at) " +
                            "VALUES (1, 1, 'p', 'ref', '/bin/sh', 'ENV', 0, 0)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_profile_signatures " +
                            "(profile_id, user_device_id, signature, timestamp, nonce, payload_json, created_at, updated_at) " +
                            "VALUES (1, 1, 'sig', 0, 'n', '{}', 0, 0)"
                    )

                    // Names and references are unique per worker.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO worker_credential_profiles " +
                                "(worker_id, name, secret_ref, command, channel, created_at, updated_at) " +
                                "VALUES (1, 'p', 'ref', '/bin/sh', 'ENV', 0, 0)"
                        )
                    }
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO worker_credential_secrets " +
                                "(worker_id, reference, encrypted_secret, encrypted_dek, algorithm, " +
                                "worker_certificate_fingerprint, created_at, updated_at) " +
                                "VALUES (1, 'ref', 'cipher', 'dek', 'alg', 'fp', 0, 0)"
                        )
                    }
                    // One signature per device and profile.
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO worker_credential_profile_signatures " +
                                "(profile_id, user_device_id, signature, timestamp, nonce, payload_json, created_at, updated_at) " +
                                "VALUES (1, 1, 'sig2', 0, 'n2', '{}', 0, 0)"
                        )
                    }
                    // A profile may name a reference that does not exist: there is no foreign key.
                    statement.executeUpdate(
                        "INSERT INTO worker_credential_profiles " +
                            "(id, worker_id, name, secret_ref, command, channel, created_at, updated_at) " +
                            "VALUES (2, 1, 'dangling', 'missing', '/bin/sh', 'ENV', 0, 0)"
                    )

                    // Deleting the secret leaves the referencing profile in place.
                    statement.executeUpdate("DELETE FROM worker_credential_secrets WHERE id = 1")
                    assertEquals(2, countRows(connection, "SELECT COUNT(*) FROM worker_credential_profiles"))

                    // Deleting the worker removes its profiles, their signatures and its secrets.
                    statement.executeUpdate("DELETE FROM workers WHERE id = 1")
                    assertEquals(0, countRows(connection, "SELECT COUNT(*) FROM worker_credential_profiles"))
                    assertEquals(
                        0,
                        countRows(connection, "SELECT COUNT(*) FROM worker_credential_profile_signatures")
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
}
