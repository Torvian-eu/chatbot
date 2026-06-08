package eu.torvian.chatbot.common.security

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test suite for JvmAsymmetricCryptoProvider.
 *
 * This tests the JVM Ed25519 signing and verification implementation
 * that uses BouncyCastle for cryptographic operations.
 */
class JvmAsymmetricCryptoProviderTest {

    private lateinit var cryptoProvider: JvmAsymmetricCryptoProvider

    @BeforeEach
    fun setup() {
        cryptoProvider = JvmAsymmetricCryptoProvider()
    }

    @Test
    fun `generateKeyPair should return a valid key pair`() = runTest {
        // Act
        val keyPairResult = cryptoProvider.generateKeyPair()

        // Assert
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        // Ed25519 public key in X.509 format is typically 32-50 bytes
        assertTrue(keyPair.publicKey.isNotEmpty(), "Public key should not be empty")

        // Private key in PKCS#8 format is typically 32-50 bytes
        assertTrue(keyPair.privateKey.isNotEmpty(), "Private key should not be empty")
    }

    @Test
    fun `generateKeyPair should return different key pairs on multiple calls`() = runTest {
        // Act
        val keyPair1Result = cryptoProvider.generateKeyPair()
        val keyPair2Result = cryptoProvider.generateKeyPair()

        // Assert
        assertTrue(keyPair1Result is Either.Right, "First key pair generation should succeed")
        assertTrue(keyPair2Result is Either.Right, "Second key pair generation should succeed")

        val keyPair1 = keyPair1Result.value
        val keyPair2 = keyPair2Result.value

        // Keys should be different
        assertTrue(keyPair1.publicKey.contentEquals(keyPair2.publicKey).not(), "Public keys should be different")
        assertTrue(keyPair1.privateKey.contentEquals(keyPair2.privateKey).not(), "Private keys should be different")
    }

    @Test
    fun `sign should return a valid Base64 encoded signature`() = runTest {
        // Arrange
        val data = "Test message to sign"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val privateKey = keyPairResult.value.privateKey

        // Act
        val signResult = cryptoProvider.sign(data, privateKey)

        // Assert
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val signature = signResult.value
        assertTrue(signature.isNotEmpty(), "Signature should not be empty")

        // Verify it's valid Base64
        val decoded = Base64.getDecoder().decode(signature)
        assertTrue(decoded.isNotEmpty(), "Signature should decode to non-empty bytes")
    }

    @Test
    fun `sign should return deterministic signatures for same data`() = runTest {
        // Arrange
        val data = "Same message signed twice"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val privateKey = keyPairResult.value.privateKey

        // Act
        val sign1Result = cryptoProvider.sign(data, privateKey)
        val sign2Result = cryptoProvider.sign(data, privateKey)

        // Assert
        assertTrue(sign1Result is Either.Right, "First signing should succeed")
        assertTrue(sign2Result is Either.Right, "Second signing should succeed")

        // Ed25519 signatures are deterministic
        assertEquals(sign1Result.value, sign2Result.value, "Ed25519 signatures should be deterministic")
    }

    @Test
    fun `verify should return true for valid signature`() = runTest {
        // Arrange
        val data = "Message to verify"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        val signResult = cryptoProvider.sign(data, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val signature = signResult.value

        // Act
        val verifyResult = cryptoProvider.verify(data, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Verification should succeed")
        assertTrue(verifyResult.value, "Valid signature should verify to true")
    }

    @Test
    fun `verify should return false for invalid signature`() = runTest {
        // Arrange
        val data = "Original message"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        val signResult = cryptoProvider.sign(data, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val validSignature = signResult.value

        // Tamper with the signature by modifying a byte
        val decoded = Base64.getDecoder().decode(validSignature)
        val tampered = decoded.copyOf()
        if (tampered.isNotEmpty()) {
            tampered[0] = (tampered[0] + 1).toByte() // Modify first byte
        }
        val tamperedSignature = Base64.getEncoder().encodeToString(tampered)

        // Act
        val verifyResult = cryptoProvider.verify(data, tamperedSignature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Verification should succeed (not throw)")
        assertTrue(verifyResult.value.not(), "Invalid signature should verify to false")
    }

    @Test
    fun `verify should return false for signature with wrong key`() = runTest {
        // Arrange
        val data = "Message to verify"
        val keyPair1Result = cryptoProvider.generateKeyPair()
        val keyPair2Result = cryptoProvider.generateKeyPair()
        assertTrue(keyPair1Result is Either.Right && keyPair2Result is Either.Right, "Key pair generation should succeed")

        val keyPair1 = keyPair1Result.value
        val keyPair2 = keyPair2Result.value

        val signResult = cryptoProvider.sign(data, keyPair1.privateKey)
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val signature = signResult.value

        // Act - Verify with wrong public key
        val verifyResult = cryptoProvider.verify(data, signature, keyPair2.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Verification should succeed (not throw)")
        assertTrue(verifyResult.value.not(), "Signature with wrong key should verify to false")
    }

    @Test
    fun `sign and verify should form a complete cycle`() = runTest {
        // Arrange
        val data = "This is a secret message for signing and verification!"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        // Act - Sign
        val signResult = cryptoProvider.sign(data, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val signature = signResult.value

        // Act - Verify
        val verifyResult = cryptoProvider.verify(data, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Verification should succeed")
        assertTrue(verifyResult.value, "Signature should be valid")
    }

    @Test
    fun `sign and verify with different data should fail`() = runTest {
        // Arrange
        val originalData = "Original message"
        val differentData = "Different message"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        val signResult = cryptoProvider.sign(originalData, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Signing should succeed")
        val signature = signResult.value

        // Act - Verify with different data
        val verifyResult = cryptoProvider.verify(differentData, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Verification should succeed (not throw)")
        assertTrue(verifyResult.value.not(), "Signature for different data should verify to false")
    }

    @Test
    fun `empty string signing and verification should work`() = runTest {
        // Arrange
        val emptyString = ""
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        // Act
        val signResult = cryptoProvider.sign(emptyString, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Empty string signing should succeed")
        val signature = signResult.value

        val verifyResult = cryptoProvider.verify(emptyString, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Empty string verification should succeed")
        assertTrue(verifyResult.value, "Empty string signature should be valid")
    }

    @Test
    fun `unicode text signing and verification should work`() = runTest {
        // Arrange
        val unicodeText = "Hello 世界! 🌍 Émojis and spëcial chars: αβγδε"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        // Act
        val signResult = cryptoProvider.sign(unicodeText, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Unicode signing should succeed")
        val signature = signResult.value

        val verifyResult = cryptoProvider.verify(unicodeText, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Unicode verification should succeed")
        assertTrue(verifyResult.value, "Unicode signature should be valid")
    }

    @Test
    fun `large text signing and verification should work`() = runTest {
        // Arrange
        val largeText = "This is a large text message. ".repeat(100) // 3000+ characters
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val keyPair = keyPairResult.value

        // Act
        val signResult = cryptoProvider.sign(largeText, keyPair.privateKey)
        assertTrue(signResult is Either.Right, "Large text signing should succeed")
        val signature = signResult.value

        val verifyResult = cryptoProvider.verify(largeText, signature, keyPair.publicKey)

        // Assert
        assertTrue(verifyResult is Either.Right, "Large text verification should succeed")
        assertTrue(verifyResult.value, "Large text signature should be valid")
    }

    @Test
    fun `verify should return error for invalid Base64 signature`() = runTest {
        // Arrange
        val data = "Test message"
        val keyPairResult = cryptoProvider.generateKeyPair()
        assertTrue(keyPairResult is Either.Right, "Key pair generation should succeed")
        val publicKey = keyPairResult.value.publicKey

        // Act
        val verifyResult = cryptoProvider.verify(data, "invalid-base64-format", publicKey)

        // Assert
        assertTrue(verifyResult is Either.Left, "Verification with invalid Base64 should fail")
        assertTrue(verifyResult.value is AsymmetricCryptoError.SignatureVerificationFailed, "Should return SignatureVerificationFailed")
    }

    @Test
    fun `sign should return error for invalid private key`() = runTest {
        // Arrange
        val data = "Test message"
        val invalidPrivateKey = ByteArray(16) { 0 } // Too short for Ed25519

        // Act
        val signResult = cryptoProvider.sign(data, invalidPrivateKey)

        // Assert
        assertTrue(signResult is Either.Left, "Signing with invalid private key should fail")
        assertTrue(signResult.value is AsymmetricCryptoError.SignatureGenerationFailed, "Should return SignatureGenerationFailed")
    }

    @Test
    fun `verify should return error for invalid public key`() = runTest {
        // Arrange
        val data = "Test message"
        val invalidPublicKey = ByteArray(16) { 0 } // Too short for Ed25519

        // Act
        val verifyResult = cryptoProvider.verify(data, "dGVzdA==", invalidPublicKey)

        // Assert
        assertTrue(verifyResult is Either.Left, "Verification with invalid public key should fail")
        assertTrue(verifyResult.value is AsymmetricCryptoError.SignatureVerificationFailed, "Should return SignatureVerificationFailed")
    }
}
