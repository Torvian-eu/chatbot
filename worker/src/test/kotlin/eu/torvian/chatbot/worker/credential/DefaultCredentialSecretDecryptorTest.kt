package eu.torvian.chatbot.worker.credential

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the worker-side unwrapping of server-encrypted credential secrets.
 *
 * The fixture produces exactly the envelope the server writes, so the test covers the two ends'
 * agreement on the algorithm identifier, the OAEP parameters and the nonce prefix rather than a single
 * side in isolation.
 */
class DefaultCredentialSecretDecryptorTest {

    /** Verifies that a value encrypted to this worker's certificate decrypts to the same text. */
    @Test
    fun `decrypts a value encrypted to the worker certificate`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("hunter2")

        val value = assertIs<SecretValue>(
            fixture.decryptor().decrypt(secret).getOrNull()
        )

        assertContentEquals("hunter2".toCharArray(), value.copyChars())
        value.close()
    }

    /** Verifies that closing a value wipes its buffer. */
    @Test
    fun `close wipes the decrypted value`() {
        val fixture = Fixture()
        val value = assertIs<SecretValue>(fixture.decryptor().decrypt(fixture.encrypt("token")).getOrNull())

        value.close()

        assertTrue(value.length > 0)
        assertContentEquals(CharArray(value.length) { '\u0000' }, value.copyChars())
    }

    /**
     * Verifies that the bytes the file channel writes are the value's UTF-8 encoding.
     *
     * The copy is produced straight from the character buffer, so non-ASCII characters and surrogate
     * pairs have to encode exactly as the value's text does.
     */
    @Test
    fun `copyBytes encodes the value as UTF-8`() {
        val text = "pässwörd-€-\uD800\uDF48"
        val value = SecretValue(text.toCharArray())

        assertContentEquals(text.encodeToByteArray(), value.copyBytes())

        value.close()
    }

    /** Verifies that a secret wrapped for another certificate is reported as a key mismatch. */
    @Test
    fun `certificate mismatch is reported as a key mismatch`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("value", certificateFingerprint = "other-fingerprint")

        val error = assertIs<SecretResolutionError.KeyMismatch>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )

        // Both fingerprints are named so an operator can tell a rotation from a corrupt payload.
        assertEquals("other-fingerprint", error.secretFingerprint)
        assertEquals(Fixture.WORKER_FINGERPRINT, error.workerFingerprint)
    }

    /** Verifies that a ciphertext wrapped with a different key fails instead of returning garbage. */
    @Test
    fun `wrong private key fails decryption`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("value")
        val foreign = Fixture()

        val error = assertIs<SecretResolutionError.DecryptionFailed>(
            foreign.decryptor().decrypt(secret).swap().getOrNull()
        )

        assertEquals(Fixture.REFERENCE, error.reference)
    }

    /** Verifies that a value above the configured limit is rejected with its bound. */
    @Test
    fun `oversized value is rejected`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("0123456789")

        val error = assertIs<SecretResolutionError.TooLarge>(
            fixture.decryptor(maxSecretBytes = 4).decrypt(secret).swap().getOrNull()
        )

        assertEquals(4, error.maxBytes)
    }

    /** Verifies that a payload which is not text is rejected rather than delivered. */
    @Test
    fun `invalid utf8 is rejected`() {
        val fixture = Fixture()
        val secret = fixture.encryptBytes(byteArrayOf(0xC3.toByte(), 0x28))

        assertIs<SecretResolutionError.InvalidValue>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )
    }

    /** Verifies that an empty value is rejected instead of attaching with an empty secret. */
    @Test
    fun `empty value is rejected`() {
        val fixture = Fixture()
        val secret = fixture.encryptBytes(ByteArray(0))

        assertIs<SecretResolutionError.InvalidValue>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )
    }

    /** Verifies that ciphertext which is not Base64 is reported as a decryption failure. */
    @Test
    fun `malformed payload is reported as a decryption failure`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("value").copy(encryptedSecret = "not base64!")

        assertIs<SecretResolutionError.DecryptionFailed>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )
    }

    /** Verifies that an unknown algorithm is rejected before any key work is attempted. */
    @Test
    fun `unsupported algorithm is rejected`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("value").copy(algorithm = "RSA1_5")

        val error = assertIs<SecretResolutionError.DecryptionFailed>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )

        assertTrue(error.reason.contains("RSA1_5"))
    }

    /** Verifies that a ciphertext shorter than its own nonce is rejected. */
    @Test
    fun `truncated ciphertext is rejected`() {
        val fixture = Fixture()
        val secret = fixture.encrypt("value").copy(
            encryptedSecret = Base64.getEncoder().encodeToString(ByteArray(4))
        )

        assertIs<SecretResolutionError.DecryptionFailed>(
            fixture.decryptor().decrypt(secret).swap().getOrNull()
        )
    }

    /**
     * Test fixture holding one worker key pair and the matching encryption routine.
     */
    private class Fixture {
        companion object {
            /** Fingerprint the fixture's certificate is assumed to have. */
            const val WORKER_FINGERPRINT = "worker-fingerprint"

            /** Reference every fixture secret carries. */
            const val REFERENCE = "vps_password"
        }

        /** Worker identity key pair the server encrypts to. */
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair()

        /** Source of data keys and nonces. */
        private val random = SecureRandom()

        /**
         * @param maxSecretBytes Largest accepted plaintext size.
         * @return Decryptor configured for this fixture's worker.
         */
        fun decryptor(maxSecretBytes: Int = 8192): CredentialSecretDecryptor =
            DefaultCredentialSecretDecryptor(
                privateKeyPem = privateKeyPem(),
                workerCertificateFingerprint = WORKER_FINGERPRINT,
                maxSecretBytes = maxSecretBytes,
            )

        /**
         * Encrypts [plaintext] the way the server does.
         *
         * @param plaintext Value to encrypt.
         * @param certificateFingerprint Fingerprint recorded with the secret.
         * @return Secret as the worker cache would hold it.
         */
        fun encrypt(
            plaintext: String,
            certificateFingerprint: String = WORKER_FINGERPRINT,
        ): EncryptedWorkerSecret = encryptBytes(plaintext.encodeToByteArray(), certificateFingerprint)

        /**
         * Encrypts raw [plaintext] bytes the way the server does.
         *
         * @param plaintext Value bytes to encrypt.
         * @param certificateFingerprint Fingerprint recorded with the secret.
         * @return Secret as the worker cache would hold it.
         */
        fun encryptBytes(
            plaintext: ByteArray,
            certificateFingerprint: String = WORKER_FINGERPRINT,
        ): EncryptedWorkerSecret {
            val dataKey = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)

            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, nonce))
            val ciphertext = nonce + aes.doFinal(plaintext)

            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.WRAP_MODE, keyPair.public, OAEP_PARAMETERS)
            val wrappedDek = rsa.wrap(SecretKeySpec(dataKey, "AES"))
            dataKey.fill(0)

            return EncryptedWorkerSecret(
                id = 1,
                workerId = 7,
                reference = REFERENCE,
                encryptedSecret = Base64.getEncoder().encodeToString(ciphertext),
                encryptedDek = Base64.getEncoder().encodeToString(wrappedDek),
                algorithm = ALGORITHM_ID,
                workerCertificateFingerprint = certificateFingerprint,
            )
        }

        /**
         * @return This fixture's private key in PEM (PKCS#8) form, as the worker stores it.
         */
        private fun privateKeyPem(): String {
            val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n"
        }
    }

    private companion object {
        /** Algorithm identifier the server records with every secret. */
        const val ALGORITHM_ID = "RSA-OAEP-256+A256GCM"

        /** Explicit OAEP parameters matching the server's encryption. */
        val OAEP_PARAMETERS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    }
}
