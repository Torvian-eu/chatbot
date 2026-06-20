package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.LocalMCPServerSignatureEntity

/**
 * Data Access Object for Local MCP server signature metadata.
 *
 * This DAO manages the persistence of signature information that links
 * Local MCP server configurations to the devices that signed them.
 */
interface LocalMCPServerSignatureDao {
    /**
     * Inserts or updates a signature record for a Local MCP server.
     *
     * If a signature already exists for the given server and device combination,
     * it will be updated. Otherwise, a new record will be inserted.
     *
     * @param entity The signature entity to upsert.
     * @return The persisted signature entity.
     */
    suspend fun upsertSignature(entity: LocalMCPServerSignatureEntity): LocalMCPServerSignatureEntity

    /**
     * Retrieves all signatures for a specific Local MCP server.
     *
     * @param serverId The Local MCP server identifier.
     * @return List of signature entities for the server, one per device.
     */
    suspend fun getSignaturesByServerId(serverId: Long): List<LocalMCPServerSignatureEntity>

    /**
     * Retrieves a specific signature for a Local MCP server and device.
     *
     * @param serverId The Local MCP server identifier.
     * @param userDeviceId The user device identifier.
     * @return The signature entity if found, or null if no signature exists.
     */
    suspend fun getSignature(serverId: Long, userDeviceId: Long): LocalMCPServerSignatureEntity?

    /**
     * Deletes the signature row for one Local MCP server and one signer device.
     *
     * Compensation flows must use this device-scoped operation because signature persistence is keyed
     * by `(serverId, userDeviceId)`, so deleting at whole-server scope would remove unrelated device rows.
     *
     * @param serverId The Local MCP server identifier.
     * @param userDeviceId The signer device identifier.
     */
    suspend fun deleteSignature(serverId: Long, userDeviceId: Long)

    /**
     * Deletes all signature records for a specific Local MCP server.
     *
     * This is typically called when a server is deleted, as the signatures
     * are no longer valid without the server configuration.
     *
     * @param serverId The Local MCP server identifier.
     */
    suspend fun deleteSignaturesByServerId(serverId: Long)
}
