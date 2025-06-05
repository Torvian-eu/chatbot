package eu.torvian.chatbot.server.data.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

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
        val secretToSave = apiSecret1
        apiSecretDao.saveSecret(secretToSave)

        val foundSecret = apiSecretDao.findSecret(apiSecret1.alias)
        assertNotNull(foundSecret, "Secret should be found after insertion")
        assertEquals(secretToSave, foundSecret)
    }

    @Test
    fun `saveSecret should update an existing secret if alias already exists`() = runTest {
        // Arrange: Insert an initial secret
        val initialEntry = apiSecret1
        testDataManager.insertApiSecret(initialEntry)

        // Act: Save a different secret with the *same* alias
        val updatedSecretData = initialEntry.copy(
            encryptedCredential = "updated_secret",
            wrappedDek = "updated_dek",
            keyVersion = 2,
            updatedAt = initialEntry.updatedAt + 60000L
        )
        apiSecretDao.saveSecret(updatedSecretData)

        // Assert: Find the secret and verify it's the updated version
        val foundSecret = apiSecretDao.findSecret(initialEntry.alias)
        assertNotNull(foundSecret, "Secret should still be found after update")
        assertEquals(updatedSecretData, foundSecret, "Found secret data should be the updated data")

    }

     @Test
     fun `findSecret should return the correct secret for a given alias`() = runTest {
         // Arrange: Insert multiple secrets
         val entry1 = apiSecret1
         val entry2 = apiSecret1.copy(alias = "alias2")
         testDataManager.setup(TestDataSet(apiSecrets = listOf(entry1, entry2)))

         // Act & Assert: Find each secret
         val found1 = apiSecretDao.findSecret(entry1.alias)
         assertNotNull(found1)
         assertEquals(entry1, found1)

         val found2 = apiSecretDao.findSecret(entry2.alias)
         assertNotNull(found2)
         assertEquals(entry2, found2)
     }

     @Test
     fun `findSecret should return null if alias does not exist`() = runTest {
         // Arrange:
         val entry1 = apiSecret1
         testDataManager.insertApiSecret(entry1)

         // Act
         val foundSecret = apiSecretDao.findSecret("non-existent-alias")

         // Assert
         assertNull(foundSecret, "Finding a non-existent alias should return null")
     }

     @Test
     fun `deleteSecret should remove the secret with the given alias`() = runTest {
         // Arrange: Insert a secret
         val entryToDelete = apiSecret1
         testDataManager.insertApiSecret(entryToDelete)

         // Verify it exists initially
         assertNotNull(apiSecretDao.findSecret(entryToDelete.alias), "Secret should exist before deletion")

         // Act
         val isDeleted = apiSecretDao.deleteSecret(entryToDelete.alias)

         // Assert
         assertTrue(isDeleted, "deleteSecret should return true on success")
         assertNull(apiSecretDao.findSecret(entryToDelete.alias), "Secret should not be found after deletion")
     }

    @Test
    fun `deleteSecret should return true if alias does not exist`() = runTest {
        // Arrange:
        val entry1 = apiSecret1
        testDataManager.insertApiSecret(entry1)

        // Act
        val isDeleted = apiSecretDao.deleteSecret("non-existent-alias")

        // Assert
        // The DAO implementation returns true even if no rows were deleted, as the state matches the requested outcome (secret is gone).
        assertTrue(isDeleted, "deleteSecret should return true even if the secret did not exist")
    }

    // Add more test cases here for edge cases, concurrency (if applicable), etc.
}