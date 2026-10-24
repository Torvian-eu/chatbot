package eu.torvian.chatbot.common.models.api.credential

/**
 * Size limit shared by the server, the worker and the app for one secret value.
 *
 * The limit exists because the delivery channels carry the value into a file or an environment entry,
 * and because an oversized secret is easier to reject at write time than to debug at attach time.
 */
object CredentialSecretLimits {
    /** Maximum size of one secret value in UTF-8 bytes. */
    const val MAX_SECRET_BYTES: Int = 8192

    /**
     * Wording for a value the delivery channels would accept but an attach could never use.
     *
     * Kept here so the app, the server and the worker report the same sentence for the same mistake.
     */
    private const val EMPTY_VALUE_VIOLATION: String = "value must be non-empty UTF-8 text"

    /**
     * Rejects a value the worker refuses when it decrypts a secret, so the mistake is caught where it is typed.
     *
     * Only emptiness is decided here: the worker refuses an empty plaintext because no channel can deliver it
     * usefully, whereas the byte limit is enforced by the encryptor, which knows the encoded size it compares.
     *
     * @param value Candidate secret value in plaintext.
     * @return A violation sentence that never echoes the value, or null when the value is acceptable.
     */
    fun validateValue(value: String): String? = if (value.isEmpty()) EMPTY_VALUE_VIOLATION else null
}
