package eu.torvian.chatbot.app.database.dao

import arrow.core.Either
import eu.torvian.chatbot.app.database.dao.error.DeleteEncryptedSecretError
import eu.torvian.chatbot.app.database.dao.error.EncryptedSecretError
import eu.torvian.chatbot.app.database.dao.error.UpdateEncryptedSecretError
import eu.torvian.chatbot.app.database.model.EncryptedSecretEntity

/**
 * Data Access Object for encrypted secrets stored in local SQLDelight database.
 *
 * Provides CRUD operations for managing encrypted secrets that can be referenced
 * from other tables via foreign keys.
 */
interface EncryptedSecretLocalDao {

    /**
     * Inserts a new encrypted secret into the database.
     *
     * @param encryptedSecret The Base64-encoded encrypted secret data
     * @param encryptedDEK The Base64-encoded encrypted Data Encryption Key
     * @param keyVersion The version of the Key Encryption Key used
     * @param createdAt Timestamp in milliseconds since epoch
     * @param updatedAt Timestamp in milliseconds since epoch
     * @return The inserted [EncryptedSecretEntity]. Unexpected errors propagate as exceptions.
     */
    suspend fun insert(
        encryptedSecret: String,
        encryptedDEK: String,
        keyVersion: Int,
        createdAt: Long,
        updatedAt: Long
    ): EncryptedSecretEntity

    /**
     * Updates an existing encrypted secret.
     *
     * @param id The ID of the secret to update
     * @param encryptedSecret The new Base64-encoded encrypted secret data
     * @param encryptedDEK The new Base64-encoded encrypted Data Encryption Key
     * @param keyVersion The new key version
     * @param updatedAt The update timestamp in milliseconds since epoch
     * @return Either an [UpdateEncryptedSecretError] or Unit on success
     */
    suspend fun update(
        id: Long,
        encryptedSecret: String,
        encryptedDEK: String,
        keyVersion: Int,
        updatedAt: Long
    ): Either<UpdateEncryptedSecretError, Unit>

    /**
     * Retrieves an encrypted secret by its ID.
     *
     * @param id The ID of the secret
     * @return Either [EncryptedSecretError.NotFound] or the encrypted secret entity
     */
    suspend fun getById(id: Long): Either<EncryptedSecretError.NotFound, EncryptedSecretEntity>

    /**
     * Deletes an encrypted secret by its ID.
     *
     * Note: This should only be called when no other tables reference this secret.
     * Consider adding cascade delete constraints or reference counting.
     *
     * @param id The ID of the secret to delete
     * @return Either a [DeleteEncryptedSecretError] or Unit on success
     */
    suspend fun deleteById(id: Long): Either<DeleteEncryptedSecretError, Unit>

    /**
     * Retrieves all encrypted secrets.
     *
     * This is mainly for debugging and administrative purposes.
     *
     * @return List of all encrypted secret entities
     */
    suspend fun getAll(): List<EncryptedSecretEntity>
}
