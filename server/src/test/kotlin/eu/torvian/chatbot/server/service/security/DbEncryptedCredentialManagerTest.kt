package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.security.CryptoError
import eu.torvian.chatbot.common.security.EncryptedSecret
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError.SecretAlreadyExists
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError.SecretNotFound
import eu.torvian.chatbot.server.service.security.error.CredentialError.CredentialNotFound
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Unit tests for [DbEncryptedCredentialManager].
 *
 * This test suite verifies the core orchestration logic of the database-backed credential manager,
 * ensuring it correctly interacts with its dependencies: [EncryptionService] and [ApiSecretDao].
 *
 * Both dependencies are mocked using MockK, focusing on testing the logic *within* the manager
 * and how it responds to success/failure from its collaborators, rather than testing the
 * dependencies themselves.
 */
class DbEncryptedCredentialManagerTest {

    private lateinit var apiSecretDao: ApiSecretDao // The mocked DAO
    private lateinit var encryptionService: EncryptionService // The mocked service
    private lateinit var credentialManager: CredentialManager // The class under test

    // --- Test Data ---
    private val testPlainCredential = "my_super_secret_api_key"
    private val testAlias = "test-alias-123" // A specific alias for lookup/delete tests
    private val testKeyVersion = 1

    private val testEncryptedData = EncryptedSecret(
        encryptedSecret = "encrypted_credential_data",
        encryptedDEK = "wrapped_data_encryption_key",
        keyVersion = testKeyVersion
    )

    @BeforeEach
    fun setUp() {
        // Mock both dependencies
        apiSecretDao = mockk()
        encryptionService = mockk()

        // Create the manager instance with mocked dependencies
        credentialManager = DbEncryptedCredentialManager(encryptionService, apiSecretDao)
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(apiSecretDao, encryptionService)
    }

    // --- storeCredential Tests ---

    @Test
    fun `storeCredential should encrypt and save credential successfully`() = runTest {
        // Arrange
        // Configure the mock encryption service to return specific encrypted data (now returns Either)
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData.right()

        // Configure the mock apiSecretDao to succeed (return Unit.right() for a successful save)
        coEvery { apiSecretDao.saveSecret(any(), eq(testEncryptedData)) } returns Unit.right()

        // Act
        val resultAlias = credentialManager.storeCredential(testPlainCredential)

        // Assert
        assertNotNull(resultAlias, "storeCredential should return a non-null alias on success")
        // Verify the alias is a valid UUID string (optional but good sanity check)
        assertDoesNotThrow { UUID.fromString(resultAlias) }

        // Verify interactions with dependencies
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        coVerify(exactly = 1) { apiSecretDao.saveSecret(any(), eq(testEncryptedData)) }
    }

    @Test
    fun `storeCredential should throw exception when encryption fails`() = runTest {
        // Arrange
        val encryptionError = CryptoError.EncryptionError("Failed to encrypt")
        every { encryptionService.encrypt(testPlainCredential) } returns encryptionError.left()

        // Act & Assert
        val exception = assertThrows<IllegalStateException> {
            credentialManager.storeCredential(testPlainCredential)
        }

        assertEquals(exception.message?.contains("Failed to encrypt credential"), true)

        // Verify that encryption was attempted but DAO was never called
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        coVerify(exactly = 0) { apiSecretDao.saveSecret(any(), any()) }
    }

    @Test
    fun `storeCredential should throw exception when DAO save fails`() = runTest {
        // Arrange
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData.right()
        coEvery { apiSecretDao.saveSecret(any(), eq(testEncryptedData)) } returns SecretAlreadyExists("alias").left()

        // Act & Assert
        val exception = assertThrows<IllegalStateException> {
            credentialManager.storeCredential(testPlainCredential)
        }

        assertEquals(exception.message?.contains("Failed to save secret to database"), true)

        // Verify interactions
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        coVerify(exactly = 1) { apiSecretDao.saveSecret(any(), eq(testEncryptedData)) }
    }

    // --- getCredential Tests ---

    @Test
    fun `getCredential should retrieve and decrypt credential successfully`() = runTest {
        // Arrange
        coEvery { apiSecretDao.getSecret(testAlias) } returns testEncryptedData.right()
        every { encryptionService.decrypt(testEncryptedData) } returns testPlainCredential.right()

        // Act
        val result = credentialManager.getCredential(testAlias)

        // Assert
        assertIs<Either.Right<String>>(result)
        assertEquals(testPlainCredential, result.value)

        // Verify interactions
        coVerify(exactly = 1) { apiSecretDao.getSecret(testAlias) }
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }

    @Test
    fun `getCredential should return CredentialNotFound when alias does not exist`() = runTest {
        // Arrange
        coEvery { apiSecretDao.getSecret(testAlias) } returns SecretNotFound(testAlias).left()

        // Act
        val result = credentialManager.getCredential(testAlias)

        // Assert
        assertIs<Either.Left<CredentialNotFound>>(result)
        assertEquals(testAlias, result.value.alias)

        // Verify that only the DAO was called, not the encryption service
        coVerify(exactly = 1) { apiSecretDao.getSecret(testAlias) }
        verify(exactly = 0) { encryptionService.decrypt(any()) }
    }

    @Test
    fun `getCredential should return CredentialNotFound when decryption fails`() = runTest {
        // Arrange
        coEvery { apiSecretDao.getSecret(testAlias) } returns testEncryptedData.right()
        val decryptionError = CryptoError.DecryptionError("Failed to decrypt")
        every { encryptionService.decrypt(testEncryptedData) } returns decryptionError.left()

        // Act
        val result = credentialManager.getCredential(testAlias)

        // Assert - Now expects Either.Left with CredentialNotFound instead of exception
        assertIs<Either.Left<CredentialNotFound>>(result)
        assertEquals(testAlias, result.value.alias)

        // Verify interactions
        coVerify(exactly = 1) { apiSecretDao.getSecret(testAlias) }
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }

    // --- deleteCredential Tests ---

    @Test
    fun `deleteCredential should delete credential successfully`() = runTest {
        // Arrange
        coEvery { apiSecretDao.deleteSecret(testAlias) } returns Unit.right()

        // Act
        val result = credentialManager.deleteCredential(testAlias)

        // Assert
        assertIs<Either.Right<Unit>>(result)

        // Verify interactions
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
    }

    @Test
    fun `deleteCredential should return CredentialNotFound when alias does not exist`() = runTest {
        // Arrange
        coEvery { apiSecretDao.deleteSecret(testAlias) } returns SecretNotFound(testAlias).left()

        // Act
        val result = credentialManager.deleteCredential(testAlias)

        // Assert
        assertIs<Either.Left<CredentialNotFound>>(result)
        assertEquals(testAlias, result.value.alias)

        // Verify interactions
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
    }
}