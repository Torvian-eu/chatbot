package eu.torvian.chatbot.common.security

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
    fun `generateDEK should return a non-empty Base64 string`() {
        // Act
        val dek = cryptoProvider.generateDEK()

        // Assert
        assertTrue(dek.isNotEmpty())
        // Verify it's a valid Base64 string
        Base64.getDecoder().decode(dek)
    }

    @Test
    fun `encryptData and decryptData should form a complete cycle`() {
        // Arrange
        val plainText = "This is a secret message"
        val dek = cryptoProvider.generateDEK()

        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertNotEquals(plainText, cipherText, "Encrypted text should be different from plain text")
        assertEquals(plainText, decryptedText, "Decrypted text should match original plain text")
    }

    @Test
    fun `wrapDEK and unwrapDEK should form a complete cycle`() {
        // Arrange
        val dek = cryptoProvider.generateDEK()

        // Act
        val wrappedDek = cryptoProvider.wrapDEK(dek)
        val unwrappedDek = cryptoProvider.unwrapDEK(wrappedDek, 1)

        // Assert
        assertNotEquals(dek, wrappedDek, "Wrapped DEK should be different from original DEK")
        assertEquals(dek, unwrappedDek, "Unwrapped DEK should match original DEK")
    }

    @Test
    fun `getKeyVersion should return the configured key version`() {
        // Act
        val keyVersion = cryptoProvider.getKeyVersion()

        // Assert
        assertEquals(config.keyVersion, keyVersion)
    }

    @Test
    fun `encryptData should produce different ciphertexts for the same plaintext`() {
        // Arrange
        val plainText = "This is a secret message"
        val dek = cryptoProvider.generateDEK()

        // Act
        val cipherText1 = cryptoProvider.encryptData(plainText, dek)
        val cipherText2 = cryptoProvider.encryptData(plainText, dek)

        // Assert
        assertNotEquals(
            cipherText1, cipherText2,
            "Encrypting the same plaintext twice should produce different ciphertexts due to random IV"
        )
    }

    @Test
    fun `wrapDEK should produce different wrapped keys for the same DEK`() {
        // Arrange
        val dek = cryptoProvider.generateDEK()

        // Act
        val wrappedDek1 = cryptoProvider.wrapDEK(dek)
        val wrappedDek2 = cryptoProvider.wrapDEK(dek)

        // Assert
        assertNotEquals(
            wrappedDek1, wrappedDek2,
            "Wrapping the same DEK twice should produce different results due to random IV"
        )
    }

    @Test
    fun `encryptData should handle empty string`() {
        // Arrange
        val plainText = ""
        val dek = cryptoProvider.generateDEK()

        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertEquals(plainText, decryptedText, "Should correctly encrypt and decrypt empty string")
    }

    @Test
    fun `encryptData should handle large string`() {
        // Arrange
        val plainText = "A".repeat(10000) // 10KB string
        val dek = cryptoProvider.generateDEK()

        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)

        // Assert
        assertEquals(plainText, decryptedText, "Should correctly encrypt and decrypt large string")
    }
}