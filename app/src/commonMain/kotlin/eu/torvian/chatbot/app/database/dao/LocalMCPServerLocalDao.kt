package eu.torvian.chatbot.app.database.dao

import arrow.core.Either
import eu.torvian.chatbot.app.database.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.GetLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.UpdateLocalMCPServerError
import eu.torvian.chatbot.app.domain.models.LocalMCPServer

/**
 * Data Access Object for LocalMCPServer entities (client-side full storage).
 *
 * This DAO handles client-side operations for Local MCP Server configurations,
 * including full configuration storage and environment variable encryption.
 *
 * Each platform (Desktop, Android, WASM) has its own independent SQLDelight database
 * with no synchronization between platforms (commands/paths differ per platform).
 *
 * Environment variables are encrypted using EncryptedSecretService and stored in
 * EncryptedSecretTable, referenced via foreign key.
 */
interface LocalMCPServerLocalDao {
    /**
     * Inserts a new LocalMCPServer configuration into the local database.
     *
     * This method will:
     * 1. Encrypt environment variables using EncryptedSecretService
     * 2. Store the encrypted secret and get the secret ID
     * 3. Insert the server configuration with the secret ID reference
     *
     * @param server The server configuration to insert (id must be pre-generated from server API)
     * @return The inserted LocalMCPServer with all fields populated
     * @throws IllegalStateException if insertion fails
     */
    suspend fun insert(server: LocalMCPServer): LocalMCPServer

    /**
     * Updates an existing LocalMCPServer configuration.
     *
     * This method will:
     * 1. Re-encrypt environment variables if they changed
     * 2. Update or create the encrypted secret
     * 3. Update the server configuration
     *
     * @param server The server configuration with updated values
     * @return Either an [UpdateLocalMCPServerError] or Unit on success
     */
    suspend fun update(server: LocalMCPServer): Either<UpdateLocalMCPServerError, Unit>

    /**
     * Deletes a LocalMCPServer configuration from the local database.
     *
     * This method will:
     * 1. Delete the server configuration row
     * 2. Delete the associated encrypted secret (if not referenced elsewhere)
     *
     * @param id The unique identifier of the server to delete
     * @return Either a [DeleteLocalMCPServerError] or Unit on success
     */
    suspend fun delete(id: Long): Either<DeleteLocalMCPServerError, Unit>

    /**
     * Retrieves a LocalMCPServer configuration by ID.
     *
     * This method will:
     * 1. Query the server configuration from the database
     * 2. Decrypt environment variables using EncryptedSecretService
     * 3. Map the database row to LocalMCPServer model
     *
     * @param id The unique identifier of the server
     * @return Either [GetLocalMCPServerError] (NotFound or DecryptionFailed) or the [LocalMCPServer]
     */
    suspend fun getById(id: Long): Either<GetLocalMCPServerError, LocalMCPServer>

    /**
     * Retrieves all LocalMCPServer configurations for a specific user.
     *
     * Environment variables are decrypted for all returned servers.
     *
     * @param userId The ID of the user
     * @return List of LocalMCPServer configurations (empty list if none)
     */
    suspend fun getAll(userId: Long): List<LocalMCPServer>

    /**
     * Retrieves only globally enabled LocalMCPServer configurations for a specific user.
     *
     * This filters servers where isEnabled = true.
     * Environment variables are decrypted for all returned servers.
     *
     * @param userId The ID of the user
     * @return List of enabled LocalMCPServer configurations (empty list if none)
     */
    suspend fun getAllEnabled(userId: Long): List<LocalMCPServer>

    /**
     * Checks if a LocalMCPServer with the specified name already exists for a user.
     *
     * Used for validation before insert/update operations.
     *
     * @param name The server name to check
     * @param userId The user ID
     * @param excludeId Optional server ID to exclude from the check (for updates)
     * @return true if a server with this name exists, false otherwise
     */
    suspend fun existsByName(name: String, userId: Long, excludeId: Long? = null): Boolean
}

