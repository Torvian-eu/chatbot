package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.security.PemPrivateKeyLoader
import eu.torvian.chatbot.worker.security.PrivateKeyParseError
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid-envelope decryptor for server-encrypted credential secrets.
 *
 * The server wraps a random AES data key with the worker certificate's public half and encrypts the
 * value with that data key, so unwrapping needs only the worker private key that already signs
 * protocol challenges. The OAEP parameters are stated explicitly: the JCE default MGF1 digest differs
 * between providers, and an implicit specification would silently fail to unwrap ciphertext the
 * server produced.
 *
 * The private key is parsed once and kept; plaintext is never cached between calls, and no value or
 * ciphertext is logged.
 *
 * @property privateKeyPem PEM private key of the worker identity.
 * @property workerCertificateFingerprint Fingerprint of the certificate whose public half the server
 *   encrypts to, used to distinguish a rotated certificate from a corrupt payload.
 * @property maxSecretBytes Largest accepted plaintext size.
 */
internal class DefaultCredentialSecretDecryptor(
    private val privateKeyPem: String,
    private val workerCertificateFingerprint: String,
    private val maxSecretBytes: Int,
) : CredentialSecretDecryptor {

    /** Parsed key, retained so a repeated attach does not re-parse the PEM. */
    private val privateKey: Either<PrivateKeyParseError, PrivateKey> by lazy { PemPrivateKeyLoader.load(privateKeyPem) }

    override fun decrypt(secret: EncryptedWorkerSecret): Either<SecretResolutionError, SecretValue> = either {
        if (secret.algorithm != ALGORITHM_ID) {
            raise(
                SecretResolutionError.DecryptionFailed(
                    secret.reference,
                    "unsupported algorithm '${secret.algorithm}'"
                )
            )
        }
        // Compared before any cryptographic work, so a rotated certificate is reported as such
        // instead of as a decryption failure.
        if (secret.workerCertificateFingerprint != workerCertificateFingerprint) {
            raise(
                SecretResolutionError.KeyMismatch(
                    reference = secret.reference,
                    secretFingerprint = secret.workerCertificateFingerprint,
                    workerFingerprint = workerCertificateFingerprint,
                )
            )
        }

        val key = privateKey.fold(
            { error ->
                raise(
                    SecretResolutionError.DecryptionFailed(
                        secret.reference,
                        "private key: ${error.reason}"
                    )
                )
            },
            { it },
        )

        val wrappedDek = decode(secret.encryptedDek, secret.reference, "the wrapped data key")
        val ciphertext = decode(secret.encryptedSecret, secret.reference, "the ciphertext")
        ensure(ciphertext.size > GCM_NONCE_BYTES) {
            SecretResolutionError.DecryptionFailed(
                secret.reference,
                "the ciphertext is too short"
            )
        }

        val dataKey = unwrapDataKey(key, wrappedDek, secret.reference)
        val plaintext = try {
            decryptValue(dataKey, ciphertext, secret.reference)
        } finally {
            // The data key is useless after this call, whether it succeeded or not.
            dataKey.fill(0)
        }
        val chars = try {
            decodeUtf8(plaintext, secret.reference)
        } finally {
            plaintext.fill(0)
        }

        SecretValue(chars)
    }

    /**
     * Decodes one Base64 field.
     *
     * @param encoded Base64 text as delivered by the server.
     * @param reference Reference the failure belongs to.
     * @param field Human-readable name of the field, used in the failure reason.
     * @return The decoded bytes.
     */
    private fun Raise<SecretResolutionError>.decode(
        encoded: String,
        reference: String,
        field: String,
    ): ByteArray = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        raise(SecretResolutionError.DecryptionFailed(reference, "$field is not valid Base64"))
    }

    /**
     * Unwraps the data key with the worker private key.
     *
     * @receiver The Raise context used to propagate a typed failure.
     * @param key Worker private key.
     * @param wrappedDek Wrapped data key.
     * @param reference Reference the failure belongs to.
     * @return The unwrapped AES key.
     */
    private fun Raise<SecretResolutionError>.unwrapDataKey(
        key: PrivateKey,
        wrappedDek: ByteArray,
        reference: String,
    ): ByteArray = try {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.UNWRAP_MODE, key, OAEP_PARAMETERS)
        cipher.unwrap(wrappedDek, AES_ALGORITHM, Cipher.SECRET_KEY).encoded
            ?: raise(
                SecretResolutionError.DecryptionFailed(reference, "the unwrapped data key has no encoding")
            )
    } catch (_: Exception) {
        raise(SecretResolutionError.DecryptionFailed(reference, "the data key could not be unwrapped"))
    }

    /**
     * Decrypts the value and enforces the size limit on the plaintext.
     *
     * @receiver The Raise context used to propagate a typed failure.
     * @param dataKey Unwrapped AES key.
     * @param ciphertext Nonce-prefixed ciphertext.
     * @param reference Reference the failure belongs to.
     * @return The plaintext bytes.
     */
    private fun Raise<SecretResolutionError>.decryptValue(
        dataKey: ByteArray,
        ciphertext: ByteArray,
        reference: String,
    ): ByteArray {
        val plaintext = try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dataKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, ciphertext, 0, GCM_NONCE_BYTES)
            )
            cipher.doFinal(ciphertext, GCM_NONCE_BYTES, ciphertext.size - GCM_NONCE_BYTES)
        } catch (_: Exception) {
            raise(SecretResolutionError.DecryptionFailed(reference, "the value could not be decrypted"))
        }

        if (plaintext.size > maxSecretBytes) {
            plaintext.fill(0)
            raise(SecretResolutionError.TooLarge(reference, maxSecretBytes))
        }
        return plaintext
    }

    /**
     * Decodes strict UTF-8 and rejects an empty value.
     *
     * @receiver The Raise context used to propagate a typed failure.
     * @param plaintext Decrypted value bytes.
     * @param reference Reference the failure belongs to.
     * @return The value as characters.
     */
    private fun Raise<SecretResolutionError>.decodeUtf8(
        plaintext: ByteArray,
        reference: String,
    ): CharArray {
        ensure(!plaintext.isEmpty()) { SecretResolutionError.InvalidValue(reference) }

        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
        } catch (_: CharacterCodingException) {
            raise(SecretResolutionError.InvalidValue(reference))
        }

        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        return chars
    }

    private companion object {
        /** Algorithm identifier the server records with every secret. */
        const val ALGORITHM_ID = "RSA-OAEP-256+A256GCM"

        /** Data-key algorithm. */
        const val AES_ALGORITHM = "AES"

        /** GCM nonce size in bytes, prefixed to the stored ciphertext. */
        const val GCM_NONCE_BYTES = 12

        /** GCM authentication tag size in bits. */
        const val GCM_TAG_BITS = 128

        /** AES-GCM transformation used for the value. */
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"

        /** RSA key-unwrap transformation used for the data key. */
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** Explicit OAEP parameters matching the server's encryption. */
        val OAEP_PARAMETERS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    }
}
