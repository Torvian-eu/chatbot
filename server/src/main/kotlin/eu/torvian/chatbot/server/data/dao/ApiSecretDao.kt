package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.models.ApiSecretEntity

/**
 * Repository interface for managing encrypted API secrets in the database.
 *
 * Defines operations for saving, retrieving, and deleting encrypted secrets
 * stored in the dedicated `api_secrets` table.
 */
interface ApiSecretDao {

    /**
     * Saves an encrypted secret to the database.
     * If a secret with the given alias already exists, it should be overwritten (upsert logic).
     *
     * @param apiSecretEntity The encrypted secret data to save.
     */
    suspend fun saveSecret(apiSecretEntity: ApiSecretEntity)

    /**
     * Finds an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to find.
     * @return The encrypted secret data if found, null otherwise.
     */
    suspend fun findSecret(alias: String): ApiSecretEntity?

    /**
     * Deletes an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to delete.
     * @return True if the secret was deleted successfully (or didn't exist), false otherwise.
     */
    suspend fun deleteSecret(alias: String): Boolean
}
