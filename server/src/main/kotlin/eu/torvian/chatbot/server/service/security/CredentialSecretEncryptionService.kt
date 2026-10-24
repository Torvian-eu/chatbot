package eu.torvian.chatbot.server.service.security

import arrow.core.Either

/**
 * Encrypts a credential secret so that only one worker can decrypt it.
 *
 * The server keeps no key material of its own here: the value is encrypted with a fresh data key and
 * that key is wrapped with the worker certificate's public half, so a database leak does not disclose
 * a secret and the server cannot read one back.
 */
interface CredentialSecretEncryptionService {
    /**
     * Encrypts [plaintext] to the worker whose certificate is [workerCertificatePem].
     *
     * @param plaintext Secret value to encrypt. Never logged, echoed, or persisted.
     * @param workerCertificatePem PEM certificate whose public key wraps the data key.
     * @return Either a typed failure or the ciphertext, the wrapped data key and their metadata.
     */
    fun encrypt(
        plaintext: String,
        workerCertificatePem: String,
    ): Either<CredentialEncryptionError, EncryptedCredentialPayload>
}

/**
 * One encrypted credential payload.
 *
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer wrap, so the worker selects the right key
 *   transformation.
 * @property workerCertificateFingerprint Fingerprint of the certificate that wrapped the data key.
 */
data class EncryptedCredentialPayload(
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
)

/**
 * Logical failure returned while encrypting a credential secret.
 *
 * Messages describe the certificate or the operation, never the plaintext.
 */
sealed interface CredentialEncryptionError {
    /**
     * The supplied certificate could not be parsed.
     *
     * @property reason Diagnostic without any secret material.
     */
    data class InvalidCertificate(val reason: String) : CredentialEncryptionError

    /**
     * The certificate carries a key that cannot wrap a data key.
     *
     * @property reason Diagnostic without any secret material.
     */
    data class UnsupportedKey(val reason: String) : CredentialEncryptionError

    /**
     * A cryptographic operation failed.
     *
     * @property reason Diagnostic without any secret material.
     */
    data class CryptoFailure(val reason: String) : CredentialEncryptionError

    /**
     * The value exceeds the shared size limit.
     *
     * @property actualBytes Size of the rejected value in UTF-8 bytes.
     * @property maxBytes Limit that was exceeded.
     */
    data class ValueTooLarge(val actualBytes: Int, val maxBytes: Int) : CredentialEncryptionError
}
