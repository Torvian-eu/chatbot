package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError
import eu.torvian.chatbot.server.domain.security.EncryptedSecret

/**
 * Repository interface for managing encrypted API secrets in the database.
 *
 * Defines operations for saving, retrieving, and deleting encrypted secrets
 * stored in the dedicated `api_secrets` table.
 */
interface ApiSecretDao {
    /**
     * Saves an encrypted secret to the database.
     *
     * @param alias The unique identifier (UUID) for the secret.
     * @param encryptedSecret The encrypted secret data to save.
     *
     * @return Either a [ApiSecretError.SecretAlreadyExists] if a secret with the same alias already exists,
     *         or [Unit] on success.
     */
    suspend fun saveSecret(
        alias: String,
        encryptedSecret: EncryptedSecret
    ): Either<ApiSecretError.SecretAlreadyExists, Unit>

    /**
     * Retrieves an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to find.
     * @return Either a [ApiSecretError.SecretNotFound] if the secret doesn't exist, or the [EncryptedSecret].
     */
    suspend fun getSecret(alias: String): Either<ApiSecretError.SecretNotFound, EncryptedSecret>

    /**
     * Deletes an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to delete.
     * @return Either a [ApiSecretError.SecretNotFound] if the secret doesn't exist, or [Unit] on success.
     */
    suspend fun deleteSecret(alias: String): Either<ApiSecretError.SecretNotFound, Unit>
}
