package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.security.EncryptedSecret
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError
import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ApiSecretDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ApiSecretDao]:
 * - Saving secrets (insert and update)
 * - Finding secrets by alias
 * - Deleting secrets by alias
 * - Handling cases where secrets don't exist.
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ApiSecretDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var apiSecretDao: ApiSecretDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val apiSecret1 = ApiSecretEntity(
        alias = "alias1",
        encryptedCredential = "encrypted_secret1",
        wrappedDek = "encrypted_dek1",
        keyVersion = 1,
        createdAt = TestDefaults.DEFAULT_INSTANT_MILLIS,
        updatedAt = TestDefaults.DEFAULT_INSTANT_MILLIS
    )

    private val encryptedSecret1 = EncryptedSecret(
        encryptedSecret = apiSecret1.encryptedCredential,
        encryptedDEK = apiSecret1.wrappedDek,
        keyVersion = apiSecret1.keyVersion
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        apiSecretDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.API_SECRETS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `saveSecret should insert a new secret if alias does not exist`() = runTest {
        // Act: Save a new secret
        val result = apiSecretDao.saveSecret(apiSecret1.alias, encryptedSecret1)

        // Assert: Should be successful (Right)
        assertTrue(result.isRight())

        // Verify the secret was saved correctly
        val foundSecret = apiSecretDao.getSecret(apiSecret1.alias)
        assertTrue(foundSecret.isRight(), "Secret should be found after insertion")
        assertEquals(encryptedSecret1, foundSecret.getOrNull(), "Retrieved secret should match saved secret")
    }

    @Test
    fun `saveSecret should fail if alias already exists`() = runTest {
        // Arrange: Insert an initial secret
        testDataManager.insertApiSecret(apiSecret1)

        // Act: Try to save a secret with the same alias
        val updatedSecret = EncryptedSecret(
            encryptedSecret = "updated_secret",
            encryptedDEK = "updated_dek",
            keyVersion = 2
        )
        val result = apiSecretDao.saveSecret(apiSecret1.alias, updatedSecret)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for existing alias")
        assertTrue(
            result.leftOrNull() is ApiSecretError.SecretAlreadyExists,
            "Error should be SecretAlreadyExists"
        )
        assertEquals(
            apiSecret1.alias, (result.leftOrNull() as ApiSecretError.SecretAlreadyExists).alias,
            "Error should contain the correct alias"
        )
    }

    @Test
    fun `getSecret should return the correct secret for a given alias`() = runTest {
        // Arrange: Insert multiple secrets
        val entry1 = apiSecret1
        val entry2 =
            apiSecret1.copy(alias = "alias2", encryptedCredential = "encrypted_secret2", wrappedDek = "encrypted_dek2")
        testDataManager.setup(TestDataSet(apiSecrets = listOf(entry1, entry2)))

        // Create encrypted secret objects
        val encryptedSecret1 = EncryptedSecret(
            encryptedSecret = entry1.encryptedCredential,
            encryptedDEK = entry1.wrappedDek,
            keyVersion = entry1.keyVersion
        )
        val encryptedSecret2 = EncryptedSecret(
            encryptedSecret = entry2.encryptedCredential,
            encryptedDEK = entry2.wrappedDek,
            keyVersion = entry2.keyVersion
        )

        // Act & Assert: Find each secret
        val found1 = apiSecretDao.getSecret(entry1.alias)
        assertTrue(found1.isRight(), "Should return Right for existing secret")
        assertEquals(encryptedSecret1, found1.getOrNull())

        val found2 = apiSecretDao.getSecret(entry2.alias)
        assertTrue(found2.isRight(), "Should return Right for existing secret")
        assertEquals(encryptedSecret2, found2.getOrNull())
    }

    @Test
    fun `getSecret should return SecretNotFound if alias does not exist`() = runTest {
        // Arrange:
        val entry1 = apiSecret1
        testDataManager.insertApiSecret(entry1)

        // Act
        val result = apiSecretDao.getSecret("non-existent-alias")

        // Assert
        assertTrue(result.isLeft(), "Finding a non-existent alias should return Left")
        assertTrue(
            result.leftOrNull() is ApiSecretError.SecretNotFound,
            "Error should be SecretNotFound"
        )
        assertEquals(
            "non-existent-alias", (result.leftOrNull() as ApiSecretError.SecretNotFound).alias,
            "Error should contain the queried alias"
        )
    }

    @Test
    fun `deleteSecret should remove the secret with the given alias`() = runTest {
        // Arrange: Insert a secret
        val entryToDelete = apiSecret1
        testDataManager.insertApiSecret(entryToDelete)

        // Verify it exists initially
        val initialCheck = apiSecretDao.getSecret(entryToDelete.alias)
        assertTrue(initialCheck.isRight(), "Secret should exist before deletion")

        // Act
        val deleteResult = apiSecretDao.deleteSecret(entryToDelete.alias)

        // Assert
        assertTrue(deleteResult.isRight(), "Deletion should succeed and return Right")

        // Verify the secret no longer exists
        val afterDeletion = apiSecretDao.getSecret(entryToDelete.alias)
        assertTrue(afterDeletion.isLeft(), "Secret should not be found after deletion")
    }

    @Test
    fun `deleteSecret should return SecretNotFound for non-existent alias`() = runTest {
        // Act: Try to delete a non-existent secret
        val result = apiSecretDao.deleteSecret("non-existent-alias")

        // Assert
        assertTrue(result.isLeft(), "Deleting a non-existent alias should return Left")
        assertTrue(
            result.leftOrNull() is ApiSecretError.SecretNotFound,
            "Error should be SecretNotFound"
        )
    }
}