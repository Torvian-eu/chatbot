package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [EncryptionService].
 *
 * This test suite verifies that [EncryptionService] correctly orchestrates
 * the calls to the underlying [CryptoProvider] for encryption and decryption
 * operations, and correctly maps data to/from the [EncryptedSecret] object.
 *
 * The [CryptoProvider] dependency is mocked using MockK.
 */
class EncryptionServiceTest {

    private lateinit var cryptoProvider: CryptoProvider // The mocked CryptoProvider
    private lateinit var encryptionService: EncryptionService // The class under test

    // --- Test Data ---
    private val testPlaintext = "this is the secret data"
    private val mockGeneratedDEK = "mock-base64-generated-dek" // Mock DEK from provider
    private val mockEncryptedData = "mock-base64-encrypted-data" // Mock encrypted data from provider
    private val mockWrappedDEK = "mock-base64-wrapped-dek" // Mock wrapped DEK from provider
    private val mockKeyVersion = 5 // Mock key version from provider
    private val mockUnwrappedDEK = "mock-base64-unwrapped-dek" // Mock unwrapped DEK from provider

    // The EncryptedSecret structure that should be produced by encrypt
    private val expectedEncryptedSecret = EncryptedSecret(
        encryptedSecret = mockEncryptedData,
        encryptedDEK = mockWrappedDEK,
        keyVersion = mockKeyVersion
    )

    // An example EncryptedSecret structure that should be consumed by decrypt
    private val testEncryptedSecretInput = EncryptedSecret(
        encryptedSecret = "input-encrypted-data", // Can be different from mockEncryptedData
        encryptedDEK = "input-wrapped-dek",      // Can be different from mockWrappedDEK
        keyVersion = 99 // Can be different from mockKeyVersion
    )

    @BeforeEach
    fun setUp() {
        // Create a mock of the CryptoProvider interface
        cryptoProvider = mockk<CryptoProvider>()

        // Create the EncryptionService instance with the mocked provider
        encryptionService = EncryptionService(cryptoProvider)
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(cryptoProvider)
    }

    // --- encrypt Tests ---

    @Test
    fun `encrypt should correctly orchestrate encryption steps and return EncryptedSecret`() = runTest {
        // Arrange
        // Configure the mock CryptoProvider methods in the expected sequence (now returning Either types)
        coEvery { cryptoProvider.generateDEK() } returns mockGeneratedDEK.right()
        val dekSlotForDataEncrypt = slot<String>()
        coEvery { cryptoProvider.encryptData(testPlaintext, capture(dekSlotForDataEncrypt)) } returns mockEncryptedData.right()

        val dekSlotForWrap = slot<String>()
        coEvery { cryptoProvider.wrapDEK(capture(dekSlotForWrap)) } returns mockWrappedDEK.right()

        coEvery { cryptoProvider.getKeyVersion() } returns mockKeyVersion

        // Act
        val result = encryptionService.encrypt(testPlaintext)

        // Assert
        assertTrue(result is Either.Right, "Encryption should succeed")
        val encryptedSecret = result.value
        assertEquals(expectedEncryptedSecret, encryptedSecret, "Encryption result should match the expected EncryptedSecret")

        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        coVerifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
            cryptoProvider.wrapDEK(mockGeneratedDEK)
            cryptoProvider.getKeyVersion()
        }

        // Verify that the same DEK was used for both data encryption and wrapping
        assertEquals(mockGeneratedDEK, dekSlotForDataEncrypt.captured, "DEK used for data encryption should be the generated one")
        assertEquals(mockGeneratedDEK, dekSlotForWrap.captured, "DEK used for wrapping should be the generated one")
    }

    @Test
    fun `encrypt should return error when DEK generation fails`() = runTest {
        // Arrange
        val error = CryptoError.KeyGenerationError("Failed to generate DEK")
        coEvery { cryptoProvider.generateDEK() } returns error.left()

        // Act
        val result = encryptionService.encrypt(testPlaintext)

        // Assert
        assertTrue(result is Either.Left, "Encryption should fail when DEK generation fails")
        val resultError = result.value
        assertEquals(error, resultError, "Should return the same error from DEK generation")
    }

    @Test
    fun `encrypt should return error when data encryption fails`() = runTest {
        // Arrange
        coEvery { cryptoProvider.generateDEK() } returns mockGeneratedDEK.right()
        val error = CryptoError.EncryptionError("Failed to encrypt data")
        coEvery { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns error.left()

        // Act
        val result = encryptionService.encrypt(testPlaintext)

        // Assert
        assertTrue(result is Either.Left, "Encryption should fail when data encryption fails")
        val resultError = result.value
        assertEquals(error, resultError, "Should return the same error from data encryption")
    }

    @Test
    fun `encrypt should return error when DEK wrapping fails`() = runTest {
        // Arrange
        coEvery { cryptoProvider.generateDEK() } returns mockGeneratedDEK.right()
        coEvery { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns mockEncryptedData.right()
        val error = CryptoError.EncryptionError("Failed to wrap DEK")
        coEvery { cryptoProvider.wrapDEK(mockGeneratedDEK) } returns error.left()

        // Act
        val result = encryptionService.encrypt(testPlaintext)

        // Assert
        assertTrue(result is Either.Left, "Encryption should fail when DEK wrapping fails")
        val resultError = result.value
        assertEquals(error, resultError, "Should return the same error from DEK wrapping")
    }

    // --- decrypt Tests ---

    @Test
    fun `decrypt should correctly orchestrate decryption steps and return plaintext`() = runTest {
        // Arrange
        coEvery { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion) } returns mockUnwrappedDEK.right()
        coEvery { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } returns testPlaintext.right()

        // Act
        val result = encryptionService.decrypt(testEncryptedSecretInput)

        // Assert
        assertTrue(result is Either.Right, "Decryption should succeed")
        val decryptedText = result.value
        assertEquals(testPlaintext, decryptedText, "Decrypted text should match the expected plaintext")

        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        coVerifyOrder {
            cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion)
            cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK)
        }
    }

    @Test
    fun `decrypt should return error when DEK unwrapping fails`() = runTest {
        // Arrange
        val error = CryptoError.KeyVersionNotFound(99)
        coEvery { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion) } returns error.left()

        // Act
        val result = encryptionService.decrypt(testEncryptedSecretInput)

        // Assert
        assertTrue(result is Either.Left, "Decryption should fail when DEK unwrapping fails")
        val resultError = result.value
        assertEquals(error, resultError, "Should return the same error from DEK unwrapping")
    }

    @Test
    fun `decrypt should return error when data decryption fails`() = runTest {
        // Arrange
        coEvery { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion) } returns mockUnwrappedDEK.right()
        val error = CryptoError.DecryptionError("Failed to decrypt data")
        coEvery { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } returns error.left()

        // Act
        val result = encryptionService.decrypt(testEncryptedSecretInput)

        // Assert
        assertTrue(result is Either.Left, "Decryption should fail when data decryption fails")
        val resultError = result.value
        assertEquals(error, resultError, "Should return the same error from data decryption")
    }
}