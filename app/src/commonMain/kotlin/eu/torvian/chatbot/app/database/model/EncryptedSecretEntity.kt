package eu.torvian.chatbot.app.database.model

import eu.torvian.chatbot.common.security.EncryptedSecret

/**
 * Database entity for encrypted secrets with ID and timestamps.
 *
 * This extends the common EncryptedSecret model with database-specific fields.
 *
 * @property id Unique identifier for the encrypted secret
 * @property encryptedSecret The secret encrypted with DEK, Base64 encoded
 * @property encryptedDEK The DEK encrypted with KEK, Base64 encoded
 * @property keyVersion Version of the KEK used for encryption
 * @property createdAt Timestamp in milliseconds since epoch when the secret was created
 * @property updatedAt Timestamp in milliseconds since epoch when the secret was last updated
 */
data class EncryptedSecretEntity(
    val id: Long,
    val encryptedSecret: String,
    val encryptedDEK: String,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    /**
     * Converts to the common EncryptedSecret model (without metadata).
     *
     * @return An EncryptedSecret instance without database-specific fields
     */
    fun toEncryptedSecret(): EncryptedSecret = EncryptedSecret(
        encryptedSecret = encryptedSecret,
        encryptedDEK = encryptedDEK,
        keyVersion = keyVersion
    )
}

