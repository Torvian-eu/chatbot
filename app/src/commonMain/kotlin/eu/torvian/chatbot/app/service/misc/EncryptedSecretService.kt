package eu.torvian.chatbot.app.service.misc

import arrow.core.Either
import eu.torvian.chatbot.app.database.model.EncryptedSecretEntity

/**
 * Service API for managing encrypted secrets in local storage.
 *
 * This API defines operations for encrypting, storing, retrieving, updating
 * and deleting sensitive data using envelope encryption. Implementations
 * combine an EncryptionService with a local database DAO to provide the
 * actual behaviour.
 */
interface EncryptedSecretService {
    /**
     * Encrypts plaintext data and stores it in the database.
     *
     * @param plainText The plaintext data to encrypt and store
     * @return Either.Right with the generated secret, or Either.Left with an error
     */
    suspend fun encryptAndStore(plainText: String): Either<EncryptAndStoreError, EncryptedSecretEntity>

    /**
     * Retrieves and decrypts a secret from the database.
     *
     * @param secretId The ID of the secret to retrieve
     * @return Either.Right with the decrypted plaintext, or Either.Left with an error
     */
    suspend fun retrieveAndDecrypt(secretId: Long): Either<RetrieveAndDecryptError, String>

    /**
     * Updates an existing encrypted secret with new plaintext data.
     *
     * This re-encrypts the data and updates the database entry.
     *
     * @param secretId The ID of the secret to update
     * @param newPlainText The new plaintext data
     * @return Either.Right with Unit on success, or Either.Left with an error
     */
    suspend fun updateSecret(secretId: Long, newPlainText: String): Either<UpdateSecretError, Unit>

    /**
     * Deletes an encrypted secret from the database.
     *
     * Warning: This should only be called when the secret is no longer referenced
     * by any other tables. Consider implementing reference counting or cascade
     * delete constraints.
     *
     * @param secretId The ID of the secret to delete
     * @return Either.Right with Unit on success, or Either.Left with an error
     */
    suspend fun deleteSecret(secretId: Long): Either<DeleteSecretError, Unit>
}

