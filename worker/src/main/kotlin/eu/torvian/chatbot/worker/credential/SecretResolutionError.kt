package eu.torvian.chatbot.worker.credential

/**
 * Logical failure while resolving a profile's secret for one attach.
 *
 * Messages name the reference at most: a value is never included, and neither is ciphertext.
 */
internal sealed interface SecretResolutionError {
    /** Reference of the secret the failure belongs to. */
    val reference: String

    /**
     * The worker's cache holds no secret with this reference.
     *
     * @property reference Reference the profile asked for.
     */
    data class NotAssigned(override val reference: String) : SecretResolutionError

    /**
     * The ciphertext could not be unwrapped or decrypted.
     *
     * @property reference Reference the failure belongs to.
     * @property reason What failed, in terms of the operation rather than the value.
     */
    data class DecryptionFailed(
        override val reference: String,
        val reason: String,
    ) : SecretResolutionError

    /**
     * The secret was encrypted to a certificate other than this worker's current one.
     *
     * A rotated certificate leaves every bound secret undecryptable, because the server holds neither
     * the plaintext nor the data key, so this is reported separately from a corrupt payload.
     *
     * @property reference Reference the failure belongs to.
     * @property secretFingerprint Fingerprint recorded with the stored secret.
     * @property workerFingerprint Fingerprint of this worker's current certificate.
     */
    data class KeyMismatch(
        override val reference: String,
        val secretFingerprint: String,
        val workerFingerprint: String,
    ) : SecretResolutionError

    /**
     * The decrypted value exceeds the configured limit.
     *
     * @property reference Reference the failure belongs to.
     * @property maxBytes Largest accepted value size.
     */
    data class TooLarge(override val reference: String, val maxBytes: Int) : SecretResolutionError

    /**
     * The decrypted bytes are not non-empty valid UTF-8 text.
     *
     * @property reference Reference the failure belongs to.
     */
    data class InvalidValue(override val reference: String) : SecretResolutionError
}
