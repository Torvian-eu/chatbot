package eu.torvian.chatbot.common.security

import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        keyVersion = 99 // Can be different from mockKeyVersion (service doesn't use version for decrypt in current implementation)
    )


    @BeforeEach
    fun setUp() {
        // Create a mock of the CryptoProvider interface
        cryptoProvider = mockk<CryptoProvider>() // Explicit type is good practice

        // Create the EncryptionService instance with the mocked provider
        encryptionService = EncryptionService(cryptoProvider)

        // Note: No need to set up general mock behaviors here unless they apply to ALL tests.
        // Specific behaviors are set up within each test method.
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(cryptoProvider)
    }

    // --- encrypt Tests ---

    @Test
    fun `encrypt should correctly orchestrate encryption steps and return EncryptedSecret`() {
        // Arrange
        // Configure the mock CryptoProvider methods in the expected sequence
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        // Need to capture the DEK passed to encryptData and wrapDEK to ensure it's the one generated
        val dekSlotForDataEncrypt = slot<String>()
        every { cryptoProvider.encryptData(testPlaintext, capture(dekSlotForDataEncrypt)) } returns mockEncryptedData

        val dekSlotForWrap = slot<String>()
        every { cryptoProvider.wrapDEK(capture(dekSlotForWrap)) } returns mockWrappedDEK

        every { cryptoProvider.getKeyVersion() } returns mockKeyVersion

        // Act
        val result = encryptionService.encrypt(testPlaintext)

        // Assert
        // Verify the result matches the expected EncryptedSecret structure
        assertEquals(expectedEncryptedSecret, result, "Encryption result should match the expected EncryptedSecret")

        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        // Use ordered = true to verify the call order
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) // Assert using the *expected* value
            cryptoProvider.wrapDEK(mockGeneratedDEK)                   // Assert using the *expected* value
            cryptoProvider.getKeyVersion()
        }

        // Also verify that the same DEK generated was used for encryptData and wrapDEK
        assertEquals(
            mockGeneratedDEK,
            dekSlotForDataEncrypt.captured,
            "The DEK used for encryptData should be the one generated"
        )
        assertEquals(mockGeneratedDEK, dekSlotForWrap.captured, "The DEK used for wrapDEK should be the one generated")

        // Confirm no other calls were made on the mock besides the ones explicitly verified above
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `encrypt should propagate exception if generateDEK fails`() {
        // Arrange
        val generateDekException = RuntimeException("DEK generation failed")
        every { cryptoProvider.generateDEK() } throws generateDekException

        // Act & Assert
        // Assert that calling encrypt throws the expected exception
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if generateDEK fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(generateDekException, thrown, "The thrown exception should be the one from generateDEK")

        // Verify only generateDEK was called
        verify(exactly = 1) { cryptoProvider.generateDEK() }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `encrypt should propagate exception if encryptData fails`() {
        // Arrange
        val encryptDataException = RuntimeException("Data encryption failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } throws encryptDataException // Setup with expected args

        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if encryptData fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(encryptDataException, thrown, "The thrown exception should be the one from encryptData")

        // Verify generateDEK was called, and then encryptData was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `encrypt should propagate exception if wrapDEK fails`() {
        // Arrange
        val wrapDekException = RuntimeException("DEK wrapping failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns mockEncryptedData
        every { cryptoProvider.wrapDEK(mockGeneratedDEK) } throws wrapDekException // Setup with expected args

        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if wrapDEK fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(wrapDekException, thrown, "The thrown exception should be the one from wrapDEK")

        // Verify generateDEK and encryptData were called, and then wrapDEK was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
            cryptoProvider.wrapDEK(mockGeneratedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `encrypt should propagate exception if getKeyVersion fails`() {
        // Arrange
        val getKeyVersionException = RuntimeException("Get key version failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns mockEncryptedData
        every { cryptoProvider.wrapDEK(mockGeneratedDEK) } returns mockWrappedDEK
        every { cryptoProvider.getKeyVersion() } throws getKeyVersionException

        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if getKeyVersion fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(getKeyVersionException, thrown, "The thrown exception should be the one from getKeyVersion")

        // Verify all previous steps were called, and then getKeyVersion was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
            cryptoProvider.wrapDEK(mockGeneratedDEK)
            cryptoProvider.getKeyVersion()
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }


    // --- decrypt Tests ---

    @Test
    fun `decrypt should correctly orchestrate decryption steps and return plaintext`() {
        // Arrange
        // Configure the mock CryptoProvider methods for decryption
        // Note: The input to unwrapDEK is from testEncryptedSecretInput
        every {
            cryptoProvider.unwrapDEK(
                testEncryptedSecretInput.encryptedDEK,
                testEncryptedSecretInput.keyVersion
            )
        } returns mockUnwrappedDEK
        // Note: The input to decryptData is from testEncryptedSecretInput (cipher) and the unwrapped DEK
        every { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } returns testPlaintext

        // Act
        val resultPlaintext = encryptionService.decrypt(testEncryptedSecretInput)

        // Assert
        assertEquals(testPlaintext, resultPlaintext, "Decrypted result should match the original plaintext")

        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        // Use ordered = true to verify the call order
        verifyOrder {
            cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion)
            cryptoProvider.decryptData(
                testEncryptedSecretInput.encryptedSecret,
                mockUnwrappedDEK
            ) // Assert using the *expected* value
        }

        // Confirm no other calls were made on the mock besides the ones explicitly verified above
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `decrypt should propagate exception if unwrapDEK fails`() {
        // Arrange
        val unwrapDekException = RuntimeException("DEK unwrapping failed")
        every {
            cryptoProvider.unwrapDEK(
                testEncryptedSecretInput.encryptedDEK,
                testEncryptedSecretInput.keyVersion
            )
        } throws unwrapDekException

        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "decrypt should throw if unwrapDEK fails"
        ) {
            encryptionService.decrypt(testEncryptedSecretInput)
        }
        assertEquals(unwrapDekException, thrown, "The thrown exception should be the one from unwrapDEK")

        // Verify unwrapDEK was called (and threw)
        verify(exactly = 1) {
            cryptoProvider.unwrapDEK(
                testEncryptedSecretInput.encryptedDEK,
                testEncryptedSecretInput.keyVersion
            )
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    @Test
    fun `decrypt should propagate exception if decryptData fails`() {
        // Arrange
        val decryptDataException = RuntimeException("Data decryption failed")
        every {
            cryptoProvider.unwrapDEK(
                testEncryptedSecretInput.encryptedDEK,
                testEncryptedSecretInput.keyVersion
            )
        } returns mockUnwrappedDEK
        every { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } throws decryptDataException // Setup with expected args

        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "decrypt should throw if decryptData fails"
        ) {
            encryptionService.decrypt(testEncryptedSecretInput)
        }
        assertEquals(decryptDataException, thrown, "The thrown exception should be the one from decryptData")

        // Verify unwrapDEK was called, and then decryptData was called (and threw)
        verifyOrder {
            cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK, testEncryptedSecretInput.keyVersion)
            cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    // Note: The current decrypt implementation doesn't use keyVersion,
    // so there's no test needed for cryptoProvider.getKeyVersion() during decryption.
    // If the implementation were updated to, for example, select a KEK based on keyVersion,
    // you would add tests to cover that logic.
}