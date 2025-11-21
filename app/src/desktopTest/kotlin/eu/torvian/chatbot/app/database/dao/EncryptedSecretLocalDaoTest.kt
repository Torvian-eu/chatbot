package eu.torvian.chatbot.app.database.dao

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.torvian.chatbot.app.database.LocalDatabase
import eu.torvian.chatbot.app.database.LocalDatabaseProvider
import eu.torvian.chatbot.app.database.dao.error.DeleteEncryptedSecretError
import eu.torvian.chatbot.app.utils.transaction.SqlDelightTransactionScope
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.*
import kotlin.test.*

/**
 * Tests for EncryptedSecretLocalDaoImpl.
 *
 * These tests use an in-memory SQLite database to verify DAO operations,
 * providing a real TransactionScope with an Unconfined dispatcher.
 */
class EncryptedSecretLocalDaoTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: LocalDatabase
    private lateinit var transactionScope: TransactionScope
    private lateinit var dao: EncryptedSecretLocalDao

    @BeforeTest
    fun setup() {
        // Create in-memory database driver
        driver = JdbcSqliteDriver(
            url = JdbcSqliteDriver.IN_MEMORY,
            properties = Properties().apply {
                put("foreign_keys", "true")  // Enable foreign key constraint enforcement
            },
            schema = LocalDatabase.Schema.synchronous()
        )
        database = LocalDatabaseProvider.createDatabase(driver)

        // Create a transaction scope specifically for testing.
        // Dispatchers.Unconfined is ideal for tests as it executes coroutines immediately.
        transactionScope = SqlDelightTransactionScope(
            transacter = database,
            coroutineContext = Dispatchers.Unconfined
        )

        // Create the DAO instance with its new dependencies
        dao = EncryptedSecretLocalDaoImpl(
            queries = database.encryptedSecretTableQueries,
            transactionScope = transactionScope
        )
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    @Test
    fun `insert should create encrypted secret and return the full entity`() = runTest {
        // Given
        val encryptedSecret = "encryptedSecretData"
        val encryptedDEK = "encryptedDEKData"
        val keyVersion = 1
        val createdAt = 1000L
        val updatedAt = 1000L

        // When
        val entity = dao.insert(encryptedSecret, encryptedDEK, keyVersion, createdAt, updatedAt)

        // Then
        assertTrue(entity.id > 0, "Generated ID should be positive")
        assertEquals(encryptedSecret, entity.encryptedSecret)
        assertEquals(encryptedDEK, entity.encryptedDEK)
        assertEquals(keyVersion, entity.keyVersion)

        // Verify it can be retrieved from the database with the same data
        val retrievedEither = dao.getById(entity.id)
        assertTrue(retrievedEither.isRight(), "Should be able to retrieve inserted entity")
        val retrieved = retrievedEither.getOrNull()!!
        assertEquals(entity, retrieved)
    }

    @Test
    fun `getById should return left when secret does not exist`() = runTest {
        // When
        val result = dao.getById(999L)

        // Then
        assertTrue(result.isLeft(), "Should return Left for non-existent ID")
    }

    @Test
    fun `update should modify existing encrypted secret`() = runTest {
        // Given - insert initial secret
        val inserted = dao.insert("original", "originalDEK", 1, 1000L, 1000L)
        val id = inserted.id

        // When - update the secret
        val newEncryptedSecret = "updated"
        val newEncryptedDEK = "updatedDEK"
        val newKeyVersion = 2
        val newUpdatedAt = 2000L
        val updateResult = dao.update(id, newEncryptedSecret, newEncryptedDEK, newKeyVersion, newUpdatedAt)
        assertTrue(updateResult.isRight(), "Update should succeed")

        // Then
        val updatedEither = dao.getById(id)
        assertTrue(updatedEither.isRight())
        val updated = updatedEither.getOrNull()!!
        assertEquals(newEncryptedSecret, updated.encryptedSecret)
        assertEquals(newEncryptedDEK, updated.encryptedDEK)
        assertEquals(newKeyVersion, updated.keyVersion)
        assertEquals(1000L, updated.createdAt, "Created timestamp should not change")
        assertEquals(newUpdatedAt, updated.updatedAt)
    }

    @Test
    fun `deleteById should remove encrypted secret`() = runTest {
        // Given
        val inserted = dao.insert("secret", "dek", 1, 1000L, 1000L)
        val id = inserted.id
        val exists = dao.getById(id)
        assertTrue(exists.isRight(), "Secret should exist after insert")

        // When
        val deleteResult = dao.deleteById(id)

        // Then
        assertTrue(deleteResult.isRight(), "Delete should succeed")
        val after = dao.getById(id)
        assertTrue(after.isLeft(), "Secret should not exist after delete")
    }

    @Test
    fun `getAll should return all encrypted secrets`() = runTest {
        // Given - insert multiple secrets
        dao.insert("secret1", "dek1", 1, 1000L, 1000L)
        dao.insert("secret2", "dek2", 1, 2000L, 2000L)
        dao.insert("secret3", "dek3", 1, 3000L, 3000L)

        // When
        val all = dao.getAll()

        // Then
        assertEquals(3, all.size, "Should return all 3 secrets")
        assertEquals(setOf("secret1", "secret2", "secret3"), all.map { it.encryptedSecret }.toSet())
    }

    @Test
    fun `getAll should return empty list when no secrets exist`() = runTest {
        // When
        val all = dao.getAll()

        // Then
        assertTrue(all.isEmpty(), "Should return empty list when no secrets exist")
    }

    @Test
    fun `multiple inserts should generate unique IDs`() = runTest {
        // When
        val e1 = dao.insert("secret1", "dek1", 1, 1000L, 1000L)
        val e2 = dao.insert("secret2", "dek2", 1, 2000L, 2000L)
        val e3 = dao.insert("secret3", "dek3", 1, 3000L, 3000L)

        // Then
        assertNotEquals(e1.id, e2.id, "IDs should be unique")
        assertNotEquals(e2.id, e3.id, "IDs should be unique")
        assertNotEquals(e1.id, e3.id, "IDs should be unique")
    }

    @Test
    fun `deleteById should return ForeignKeyViolation when secret is referenced`() = runTest {
        // Given: create a dummy table with a foreign key constraint for the test
        driver.execute(
            null, """
            CREATE TABLE DummyTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                secretId INTEGER NOT NULL,
                FOREIGN KEY(secretId) REFERENCES EncryptedSecretTable(id)
            );
        """.trimIndent(), 0
        )

        // Insert a secret
        val inserted = dao.insert("encrypted-value", "dek", 1, 1000L, 1000L)
        val secretId = inserted.id

        // Insert a row in the child table referencing the secret
        driver.execute(
            null,
            "INSERT INTO DummyTable(name, secretId) VALUES ('Dummy', $secretId)",
            0
        )

        // When
        val result = dao.deleteById(secretId)

        // Then
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        assertTrue(result.leftOrNull() is DeleteEncryptedSecretError.ForeignKeyViolation)

        // Verify it still exists
        val still = dao.getById(secretId)
        assertTrue(still.isRight())
    }
}