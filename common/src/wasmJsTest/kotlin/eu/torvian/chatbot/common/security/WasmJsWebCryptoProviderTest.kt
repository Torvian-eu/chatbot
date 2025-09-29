package eu.torvian.chatbot.common.security

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Test suite for WasmJsWebCryptoProvider.
 *
 * This tests the secure WASM crypto provider implementation that uses
 * authenticated encryption, proper key derivation, and envelope encryption.
 */
@OptIn(ExperimentalEncodingApi::class)
class WasmJsWebCryptoProviderTest {

    private val testConfig = EncryptionConfig(
        masterKeys = mapOf(
            1 to Base64.Default.encode(ByteArray(32) { it.toByte() }), // Test key v1
            2 to Base64.Default.encode(ByteArray(32) { (it + 50).toByte() }) // Test key v2
        ),
        keyVersion = 1
    )

    private val cryptoProvider = WasmJsWebCryptoProvider(testConfig)

    @Test
    fun `getKeyVersion should return configured version`() {
        // Act
        val version = cryptoProvider.getKeyVersion()

        // Assert
        assertEquals(1, version)
    }

    @Test
    fun `generateDEK should return a valid Base64 encoded string`() = runTest {
        // Act
        val dekResult = cryptoProvider.generateDEK()

        // Assert
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value
        assertTrue(dek.isNotEmpty(), "DEK should not be empty")

        // Verify it's valid Base64 and decodes to 32 bytes
        val decoded = Base64.Default.decode(dek)
        assertEquals(32, decoded.size, "DEK should decode to 32 bytes (256 bits)")
    }

    @Test
    fun `generateDEK should return different values on multiple calls`() = runTest {
        // Act
        val dek1Result = cryptoProvider.generateDEK()
        val dek2Result = cryptoProvider.generateDEK()

        // Assert
        assertTrue(dek1Result is Either.Right, "First DEK generation should succeed")
        assertTrue(dek2Result is Either.Right, "Second DEK generation should succeed")
        assertNotEquals(dek1Result.value, dek2Result.value, "Generated DEKs should be different")
    }

    @Test
    fun `wrapDEK should return Base64 encoded wrapped DEK with version info`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val wrappedResult = cryptoProvider.wrapDEK(dek)

        // Assert
        assertTrue(wrappedResult is Either.Right, "DEK wrapping should succeed")
        val wrapped = wrappedResult.value
        assertTrue(wrapped.isNotEmpty(), "Wrapped DEK should not be empty")

        // Verify it's valid Base64
        val decoded = Base64.Default.decode(wrapped)
        assertTrue(decoded.size >= 17, "Wrapped DEK should have nonce(16) + data + version(1)")
        assertEquals(1, decoded.last().toInt(), "Version byte should match current version")
    }

    @Test
    fun `unwrapDEK should successfully unwrap a wrapped DEK`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val originalDek = dekResult.value

        val wrappedResult = cryptoProvider.wrapDEK(originalDek)
        assertTrue(wrappedResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedResult.value

        // Act
        val unwrappedResult = cryptoProvider.unwrapDEK(wrappedDek, 1)

        // Assert
        assertTrue(unwrappedResult is Either.Right, "DEK unwrapping should succeed")
        assertEquals(originalDek, unwrappedResult.value, "Unwrapped DEK should match original")
    }

    @Test
    fun `unwrapDEK should work with different key versions`() = runTest {
        // Arrange
        val configV2 = testConfig.copy(keyVersion = 2)
        val providerV2 = WasmJsWebCryptoProvider(configV2)

        val dekResult = providerV2.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val originalDek = dekResult.value

        val wrappedResult = providerV2.wrapDEK(originalDek)
        assertTrue(wrappedResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedResult.value

        // Act - Use original provider to unwrap with version 2 key
        val unwrappedResult = cryptoProvider.unwrapDEK(wrappedDek, 2)

        // Assert
        assertTrue(unwrappedResult is Either.Right, "DEK unwrapping with different version should succeed")
        assertEquals(originalDek, unwrappedResult.value, "Unwrapped DEK should match original")
    }

    @Test
    fun `unwrapDEK should fail with invalid format`() = runTest {
        // Act
        val result = cryptoProvider.unwrapDEK("invalid-base64", 1)

        // Assert
        assertTrue(result is Either.Left, "Unwrapping invalid format should fail")
        assertTrue(result.value is CryptoError.DecryptionError, "Should return DecryptionError")
    }

    @Test
    fun `unwrapDEK should fail with wrong key version`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        val wrappedResult = cryptoProvider.wrapDEK(dek)
        assertTrue(wrappedResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedResult.value

        // Act - Try to unwrap with wrong version
        val result = cryptoProvider.unwrapDEK(wrappedDek, 99)

        // Assert
        assertTrue(result is Either.Left, "Unwrapping with wrong version should fail")
        assertTrue(result.value is CryptoError.KeyVersionNotFound, "Should return KeyVersionNotFound")
    }

    @Test
    fun `encryptData and decryptData should form a complete cycle`() = runTest {
        // Arrange
        val plainText = "This is a secret message for WASM testing with authenticated encryption!"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act - Encrypt
        val encryptResult = cryptoProvider.encryptData(plainText, dek)
        assertTrue(encryptResult is Either.Right, "Encryption should succeed")
        val cipherText = encryptResult.value

        // Act - Decrypt
        val decryptResult = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertTrue(decryptResult is Either.Right, "Decryption should succeed")
        assertEquals(plainText, decryptResult.value, "Decrypted text should match original")
    }

    @Test
    fun `encryptData should return different ciphertext for same plaintext due to random nonce`() = runTest {
        // Arrange
        val plainText = "Same message encrypted twice"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val encrypt1Result = cryptoProvider.encryptData(plainText, dek)
        val encrypt2Result = cryptoProvider.encryptData(plainText, dek)

        // Assert
        assertTrue(encrypt1Result is Either.Right, "First encryption should succeed")
        assertTrue(encrypt2Result is Either.Right, "Second encryption should succeed")
        assertNotEquals(
            encrypt1Result.value,
            encrypt2Result.value,
            "Should produce different ciphertext due to random nonce"
        )
    }

    @Test
    fun `encryptData should return Base64 encoded ciphertext`() = runTest {
        // Arrange
        val plainText = "Test message for Base64 encoding"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val encryptResult = cryptoProvider.encryptData(plainText, dek)

        // Assert
        assertTrue(encryptResult is Either.Right, "Encryption should succeed")
        val cipherText = encryptResult.value
        assertTrue(cipherText.isNotEmpty(), "Ciphertext should not be empty")

        // Verify it's valid Base64
        val decoded = Base64.Default.decode(cipherText)
        assertTrue(decoded.size >= 13, "Ciphertext should have nonce(12) + encrypted data + auth tag")
    }

    @Test
    fun `decryptData should fail with invalid ciphertext format`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val result = cryptoProvider.decryptData("invalid-base64-format", dek)

        // Assert
        assertTrue(result is Either.Left, "Decryption with invalid format should fail")
        assertTrue(result.value is CryptoError.DecryptionError, "Should return DecryptionError")
    }

    @Test
    fun `decryptData should fail with tampered ciphertext`() = runTest {
        // Arrange
        val plainText = "Original message"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        val encryptResult = cryptoProvider.encryptData(plainText, dek)
        assertTrue(encryptResult is Either.Right, "Encryption should succeed")
        val validCipherText = encryptResult.value

        // Tamper with the ciphertext by changing one character
        val tamperedCipherText = validCipherText.dropLast(1) + "X"

        // Act
        val result = cryptoProvider.decryptData(tamperedCipherText, dek)

        // Assert
        assertTrue(result is Either.Left, "Decryption of tampered data should fail")
        assertTrue(result.value is CryptoError.DecryptionError, "Should return DecryptionError")
    }

    @Test
    fun `complete workflow should work end-to-end with proper security`() = runTest {
        // Arrange
        val originalMessage = "End-to-end test with authenticated encryption: !@#$%^&*()"

        // Act - Generate DEK
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act - Wrap DEK
        val wrappedResult = cryptoProvider.wrapDEK(dek)
        assertTrue(wrappedResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedResult.value

        // Act - Encrypt data
        val encryptResult = cryptoProvider.encryptData(originalMessage, dek)
        assertTrue(encryptResult is Either.Right, "Encryption should succeed")
        val cipherText = encryptResult.value

        // Act - Unwrap DEK
        val unwrappedResult = cryptoProvider.unwrapDEK(wrappedDek, 1)
        assertTrue(unwrappedResult is Either.Right, "DEK unwrapping should succeed")
        val unwrappedDek = unwrappedResult.value

        // Act - Decrypt data
        val decryptResult = cryptoProvider.decryptData(cipherText, unwrappedDek)
        assertTrue(decryptResult is Either.Right, "Decryption should succeed")
        val decryptedMessage = decryptResult.value

        // Assert
        assertEquals(originalMessage, decryptedMessage, "End-to-end workflow should preserve message")
        assertEquals(dek, unwrappedDek, "Unwrapped DEK should match original")
    }

    @Test
    fun `empty string encryption and decryption should work`() = runTest {
        // Arrange
        val emptyString = ""
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val encryptResult = cryptoProvider.encryptData(emptyString, dek)
        assertTrue(encryptResult is Either.Right, "Empty string encryption should succeed")
        val cipherText = encryptResult.value

        val decryptResult = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertTrue(decryptResult is Either.Right, "Empty string decryption should succeed")
        assertEquals(emptyString, decryptResult.value, "Empty string should round-trip correctly")
    }

    @Test
    fun `unicode text encryption and decryption should work`() = runTest {
        // Arrange
        val unicodeText = "Hello 世界! 🌍 Émojis and spëcial chars: αβγδε"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val encryptResult = cryptoProvider.encryptData(unicodeText, dek)
        assertTrue(encryptResult is Either.Right, "Unicode encryption should succeed")
        val cipherText = encryptResult.value

        val decryptResult = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertTrue(decryptResult is Either.Right, "Unicode decryption should succeed")
        assertEquals(unicodeText, decryptResult.value, "Unicode text should round-trip correctly")
    }

    @Test
    fun `large text encryption and decryption should work`() = runTest {
        // Arrange
        val largeText = "This is a large text message. ".repeat(100) // 3000+ characters
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val encryptResult = cryptoProvider.encryptData(largeText, dek)
        assertTrue(encryptResult is Either.Right, "Large text encryption should succeed")
        val cipherText = encryptResult.value

        val decryptResult = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertTrue(decryptResult is Either.Right, "Large text decryption should succeed")
        assertEquals(largeText, decryptResult.value, "Large text should round-trip correctly")
    }

    @Test
    fun `authentication should prevent cross-key attacks`() = runTest {
        // Arrange
        val plainText = "Secret message"
        val dek1Result = cryptoProvider.generateDEK()
        val dek2Result = cryptoProvider.generateDEK()
        assertTrue(dek1Result is Either.Right && dek2Result is Either.Right, "DEK generation should succeed")
        val dek1 = dek1Result.value
        val dek2 = dek2Result.value

        // Encrypt with first key
        val encryptResult = cryptoProvider.encryptData(plainText, dek1)
        assertTrue(encryptResult is Either.Right, "Encryption should succeed")
        val cipherText = encryptResult.value

        // Act - Try to decrypt with second key
        val decryptResult = cryptoProvider.decryptData(cipherText, dek2)

        // Assert
        assertTrue(decryptResult is Either.Left, "Decryption with wrong key should fail due to authentication")
        assertTrue(decryptResult.value is CryptoError.DecryptionError, "Should return DecryptionError")
    }
}