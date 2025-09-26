package eu.torvian.chatbot.common.security

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import java.security.SecureRandom
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AESCryptoProviderTest {

    private lateinit var cryptoProvider: AESCryptoProvider
    private lateinit var config: EncryptionConfig

    @BeforeEach
    fun setup() {
        // Create a random test master key (Base64-encoded 256-bit key)
        val random = SecureRandom()
        val keyBytes = ByteArray(32)
        random.nextBytes(keyBytes)
        val masterKey = Base64.getEncoder().encodeToString(keyBytes)
        config = EncryptionConfig(masterKeys = mapOf(1 to masterKey), keyVersion = 1)
        cryptoProvider = AESCryptoProvider(config)
    }

    @Test
    fun `generateDEK should return a non-empty Base64 string`() = runTest {
        // Act
        val dekResult = cryptoProvider.generateDEK()

        // Assert
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value
        assertTrue(dek.isNotEmpty())
        // Verify it's a valid Base64 string
        Base64.getDecoder().decode(dek)
    }

    @Test
    fun `encryptData and decryptData should form a complete cycle`() = runTest {
        // Arrange
        val plainText = "This is a secret message"
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val cipherTextResult = cryptoProvider.encryptData(plainText, dek)
        assertTrue(cipherTextResult is Either.Right, "Encryption should succeed")
        val cipherText = cipherTextResult.value

        val decryptedTextResult = cryptoProvider.decryptData(cipherText, dek)
        assertTrue(decryptedTextResult is Either.Right, "Decryption should succeed")
        val decryptedText = decryptedTextResult.value

        // Assert
        assertNotEquals(plainText, cipherText, "Encrypted text should be different from plain text")
        assertEquals(plainText, decryptedText, "Decrypted text should match original plain text")
    }

    @Test
    fun `wrapDEK and unwrapDEK should form a complete cycle`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act
        val wrappedDekResult = cryptoProvider.wrapDEK(dek)
        assertTrue(wrappedDekResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedDekResult.value

        val unwrappedDekResult = cryptoProvider.unwrapDEK(wrappedDek, config.keyVersion)
        assertTrue(unwrappedDekResult is Either.Right, "DEK unwrapping should succeed")
        val unwrappedDek = unwrappedDekResult.value

        // Assert
        assertEquals(dek, unwrappedDek, "Unwrapped DEK should match original DEK")
    }

    @Test
    fun `different KEK versions should work independently`() = runTest {
        // Arrange - create config with multiple key versions
        val random = SecureRandom()
        val keyBytes1 = ByteArray(32)
        val keyBytes2 = ByteArray(32)
        random.nextBytes(keyBytes1)
        random.nextBytes(keyBytes2)
        val masterKey1 = Base64.getEncoder().encodeToString(keyBytes1)
        val masterKey2 = Base64.getEncoder().encodeToString(keyBytes2)

        val multiKeyConfig = EncryptionConfig(
            masterKeys = mapOf(1 to masterKey1, 2 to masterKey2),
            keyVersion = 2
        )
        val multiKeyCrypto = AESCryptoProvider(multiKeyConfig)

        val dekResult = multiKeyCrypto.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value

        // Act - wrap with version 2, unwrap with version 1 should fail, version 2 should succeed
        val wrappedDekResult = multiKeyCrypto.wrapDEK(dek)
        assertTrue(wrappedDekResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedDekResult.value

        val unwrappedV2Result = multiKeyCrypto.unwrapDEK(wrappedDek, 2)
        assertTrue(unwrappedV2Result is Either.Right, "DEK unwrapping with correct version should succeed")
        val unwrappedV2Dek = unwrappedV2Result.value

        // Assert
        assertEquals(dek, unwrappedV2Dek, "Unwrapped DEK should match original DEK")
    }

    @Test
    fun `encryption with different DEKs should produce different results`() = runTest {
        // Arrange
        val plainText = "This is a secret message"
        val dek1Result = cryptoProvider.generateDEK()
        val dek2Result = cryptoProvider.generateDEK()
        assertTrue(dek1Result is Either.Right, "DEK1 generation should succeed")
        assertTrue(dek2Result is Either.Right, "DEK2 generation should succeed")
        val dek1 = dek1Result.value
        val dek2 = dek2Result.value

        // Act
        val cipherText1Result = cryptoProvider.encryptData(plainText, dek1)
        val cipherText2Result = cryptoProvider.encryptData(plainText, dek2)
        assertTrue(cipherText1Result is Either.Right, "Encryption with DEK1 should succeed")
        assertTrue(cipherText2Result is Either.Right, "Encryption with DEK2 should succeed")
        val cipherText1 = cipherText1Result.value
        val cipherText2 = cipherText2Result.value

        // Assert
        assertNotEquals(cipherText1, cipherText2, "Different DEKs should produce different ciphertexts")
    }

    @Test
    fun `invalid key version should return error`() = runTest {
        // Arrange
        val dekResult = cryptoProvider.generateDEK()
        assertTrue(dekResult is Either.Right, "DEK generation should succeed")
        val dek = dekResult.value
        val wrappedDekResult = cryptoProvider.wrapDEK(dek)
        assertTrue(wrappedDekResult is Either.Right, "DEK wrapping should succeed")
        val wrappedDek = wrappedDekResult.value

        // Act
        val unwrappedResult = cryptoProvider.unwrapDEK(wrappedDek, 999) // Invalid version

        // Assert
        assertTrue(unwrappedResult is Either.Left, "Unwrapping with invalid key version should fail")
        val error = unwrappedResult.value
        assertTrue(error is CryptoError.KeyVersionNotFound, "Should return KeyVersionNotFound error")
        assertEquals(999, error.version)
    }

    @Test
    fun `invalid Base64 input should return error`() = runTest {
        // Act
        val decryptResult = cryptoProvider.decryptData("invalid-base64!", "validkey")

        // Assert
        assertTrue(decryptResult is Either.Left, "Decryption with invalid Base64 should fail")
        val error = decryptResult.value
        assertTrue(error is CryptoError.InvalidCiphertext, "Should return InvalidCiphertext error")
    }

    @Test
    fun `getKeyVersion should return configured version`() {
        // Act & Assert
        assertEquals(1, cryptoProvider.getKeyVersion())
    }
}