package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
import kotlin.test.assertTrue

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

        // Create the class under test with mocked dependencies
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
        // Configure the mock encryption service to return specific encrypted data
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData

        // Configure the mock apiSecretDao to succeed (return Unit.right() for a successful save)
        coEvery { apiSecretDao.saveSecret(any(), eq(testEncryptedData)) } returns Unit.right()

        // Act
        val resultAlias = credentialManager.storeCredential(testPlainCredential)

        // Assert
        assertNotNull(resultAlias, "storeCredential should return a non-null alias on success")
        // Verify the alias is a valid UUID string (optional but good sanity check)
        assertDoesNotThrow { UUID.fromString(resultAlias) }

        // Verify encryptionService was called correctly
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }

        // Verify apiSecretDao.saveSecret was called correctly with the alias and encrypted data
        coVerify(exactly = 1) { apiSecretDao.saveSecret(any(), testEncryptedData) }
    }

    @Test
    fun `storeCredential should throw when encryption fails`() = runTest {
        // Arrange
        // Configure the mock encryption service to throw an exception
        val encryptionException = RuntimeException("Encryption failed!")
        every { encryptionService.encrypt(testPlainCredential) } throws encryptionException

        // Assert & Act
        // Since storeCredential doesn't return Either but throws on encryption failure,
        // we should verify the exception is propagated
        val exception = assertThrows<RuntimeException> {
            credentialManager.storeCredential(testPlainCredential)
        }
        assertEquals("Encryption failed!", exception.message)

        // Verify encryptionService was called
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        // Verify apiSecretDao.saveSecret was NOT called
        coVerify(exactly = 0) { apiSecretDao.saveSecret(any(), any()) }
    }

    @Test
    fun `storeCredential should throw if saving to database fails with duplicate alias`() = runTest {
        // Arrange
        // Configure encryption service to succeed
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData

        // Configure apiSecretDao to return SecretAlreadyExists
        coEvery { apiSecretDao.saveSecret(any(), any()) } returns SecretAlreadyExists("test-alias").left()

        // Assert & Act
        val exception = assertThrows<IllegalStateException> {
            credentialManager.storeCredential(testPlainCredential)
        }
        assertTrue(exception.message?.contains("already exists") == true)

        // Verify encryptionService was called
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        // Verify apiSecretDao.saveSecret was called
        coVerify(exactly = 1) { apiSecretDao.saveSecret(any(), any()) }
    }

    // --- getCredential Tests ---

    @Test
    fun `getCredential should retrieve and decrypt credential successfully`() = runTest {
        // Arrange
        // Configure the mock apiSecretDao to return the encrypted secret for the test alias
        coEvery { apiSecretDao.getSecret(testAlias) } returns Either.Right(testEncryptedData)

        // Configure the mock encryption service to return the plaintext when decrypting the specific encrypted data
        every { encryptionService.decrypt(testEncryptedData) } returns testPlainCredential

        // Act
        val result = credentialManager.getCredential(testAlias)

        // Assert
        assertTrue(result.isRight(), "getCredential should return Right with the credential on success")
        assertEquals(
            testPlainCredential,
            result.getOrNull(),
            "Retrieved credential should match the original plaintext"
        )

        // Verify apiSecretDao.getSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.getSecret(testAlias) }
        // Verify encryptionService.decrypt was called correctly
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }

    @Test
    fun `getCredential should return CredentialNotFound when alias is not found in database`() = runTest {
        // Arrange
        val nonExistentAlias = "non-existent-alias"
        // Configure the mock apiSecretDao to return SecretNotFound for a non-existent alias
        coEvery { apiSecretDao.getSecret(nonExistentAlias) } returns SecretNotFound(nonExistentAlias).left()

        // Act
        val result = credentialManager.getCredential(nonExistentAlias)

        // Assert
        assertTrue(result.isLeft(), "getCredential should return Left when alias is not found")
        val error = result.leftOrNull()
        assertIs<CredentialNotFound>(error, "Error should be of type CredentialNotFound")
        assertEquals(nonExistentAlias, error.alias, "Error should contain the non-existent alias")

        // Verify apiSecretDao.getSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.getSecret(nonExistentAlias) }
        // Verify encryptionService.decrypt was NOT called
        verify(exactly = 0) { encryptionService.decrypt(any()) }
    }

    @Test
    fun `getCredential should propagate decryption failure as a technical exception`() = runTest {
        // Arrange
        // Configure the mock apiSecretDao to return encrypted data for the test alias
        coEvery { apiSecretDao.getSecret(testAlias) } returns Either.Right(testEncryptedData)

        // Configure the mock encryption service to throw an exception when decrypting
        val decryptionException = RuntimeException("Decryption failed!")
        every { encryptionService.decrypt(testEncryptedData) } throws decryptionException

        // Act & Assert
        // Since the method should propagate technical errors like decryption failures,
        // we expect the exception to be thrown directly
        val exception = assertThrows<RuntimeException> {
            credentialManager.getCredential(testAlias)
        }
        assertEquals("Decryption failed!", exception.message)

        // Verify apiSecretDao.getSecret was called
        coVerify(exactly = 1) { apiSecretDao.getSecret(testAlias) }
        // Verify encryptionService.decrypt was called and threw
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }


    // --- deleteCredential Tests ---

    @Test
    fun `deleteCredential should successfully delete an existing credential`() = runTest {
        // Arrange
        // Configure the mock apiSecretDao to return success for the delete operation
        coEvery { apiSecretDao.deleteSecret(testAlias) } returns Unit.right()

        // Act
        val result = credentialManager.deleteCredential(testAlias)

        // Assert
        assertTrue(result.isRight(), "deleteCredential should return Right when successful")

        // Verify apiSecretDao.deleteSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
    }

    @Test
    fun `deleteCredential should return CredentialNotFound when alias does not exist`() = runTest {
        // Arrange
        val nonExistentAlias = "non-existent-alias"
        // Configure apiSecretDao to return SecretNotFound for a non-existent alias
        coEvery { apiSecretDao.deleteSecret(nonExistentAlias) } returns SecretNotFound(nonExistentAlias).left()

        // Act
        val result = credentialManager.deleteCredential(nonExistentAlias)

        // Assert
        assertTrue(result.isLeft(), "deleteCredential should return Left when alias is not found")
        val error = result.leftOrNull()
        assertIs<CredentialNotFound>(error, "Error should be of type CredentialNotFound")
        assertEquals(nonExistentAlias, error.alias, "Error should contain the non-existent alias")

        // Verify apiSecretDao.deleteSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(nonExistentAlias) }
    }
}