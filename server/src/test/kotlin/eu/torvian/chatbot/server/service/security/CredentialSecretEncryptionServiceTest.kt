package eu.torvian.chatbot.server.service.security

import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the hybrid envelope encryption used for worker-bound credential secrets.
 *
 * The decrypt path is implemented inside the test on purpose: it reproduces what the worker does with
 * `privateKeyPem`, so a change in the parameter specification here would show up as unreadable
 * ciphertext rather than as a passing test with a matching mistake on both sides.
 */
class CredentialSecretEncryptionServiceTest {

    private val certificateService = DefaultCertificateService()
    private val encryptionService = DefaultCredentialSecretEncryptionService(certificateService)
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val certificatePem: String = pemFor(keyPair)

    /**
     * Ensures the worker can recover the value and that both layers are present.
     */
    @Test
    fun `encrypts a value the matching private key can read`() {
        val payload = encryptionService.encrypt("s3cr3t", certificatePem).getOrNull()
            ?: error("encryption must succeed")

        assertEquals("RSA-OAEP-256+A256GCM", payload.algorithm)
        assertEquals(certificateService.computeCertificateFingerprint(
            certificateService.parseCertificate(certificatePem)
        ), payload.workerCertificateFingerprint)
        assertEquals("s3cr3t", decrypt(payload.encryptedSecret, payload.encryptedDek, keyPair))
        assertTrue(payload.encryptedSecret != payload.encryptedDek)
    }

    /**
     * Ensures a separate data key is generated for every secret, so equal values differ on the wire.
     */
    @Test
    fun `encrypting the same value twice yields different ciphertext`() {
        val first = encryptionService.encrypt("same", certificatePem).getOrNull() ?: error("first failed")
        val second = encryptionService.encrypt("same", certificatePem).getOrNull() ?: error("second failed")

        assertTrue(first.encryptedDek != second.encryptedDek)
        assertTrue(first.encryptedSecret != second.encryptedSecret)
    }

    /**
     * Ensures a different worker key cannot read the value.
     */
    @Test
    fun `another private key cannot decrypt the payload`() {
        val payload = encryptionService.encrypt("secret", certificatePem).getOrNull() ?: error("encryption failed")
        val otherKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        assertNull(runCatching { decrypt(payload.encryptedSecret, payload.encryptedDek, otherKeyPair) }.getOrNull())
    }

    /**
     * Ensures a malformed certificate is reported as a typed failure rather than an exception.
     */
    @Test
    fun `malformed certificate is a typed failure`() {
        val result = encryptionService.encrypt("secret", "not a certificate")

        assertIs<CredentialEncryptionError.InvalidCertificate>(result.leftOrNull())
    }

    /**
     * Ensures an oversized value is rejected before any cryptography happens.
     */
    @Test
    fun `oversized value is rejected`() {
        val result = encryptionService.encrypt("x".repeat(9000), certificatePem)

        assertIs<CredentialEncryptionError.ValueTooLarge>(result.leftOrNull())
    }

    /**
     * Ensures the serialised payload contains no plaintext.
     */
    @Test
    fun `payload contains no plaintext`() {
        val payload = encryptionService.encrypt("plain-text-marker", certificatePem).getOrNull()
            ?: error("encryption failed")

        listOf(payload.encryptedSecret, payload.encryptedDek, payload.algorithm).forEach { part ->
            assertTrue("plain-text-marker" !in part)
        }
    }

    /**
     * Reproduces the worker's decryption of one payload.
     *
     * @param encryptedSecret Base64 ciphertext with its nonce prefix.
     * @param encryptedDek Base64 wrapped data key.
     * @param workerKeyPair Worker identity key pair.
     * @return The recovered value.
     */
    private fun decrypt(encryptedSecret: String, encryptedDek: String, workerKeyPair: KeyPair): String {
        val unwrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        unwrap.init(
            Cipher.UNWRAP_MODE,
            workerKeyPair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        val dataKey = unwrap.unwrap(Base64.getDecoder().decode(encryptedDek), "AES", Cipher.SECRET_KEY)

        val raw = Base64.getDecoder().decode(encryptedSecret)
        val nonce = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey.encoded, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(body).decodeToString()
    }

    /**
     * @param keyPair Worker identity key pair.
     * @return PEM certificate for that key pair, as the server stores it on the worker row.
     */
    private fun pemFor(keyPair: KeyPair): String {
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=worker-test",
            validityYears = 1
        )
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }
}
