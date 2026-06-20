package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import kotlin.time.Clock

/**
 * Exposed table for Local MCP server signature metadata.
 *
 * This table stores signature information for Local MCP server configurations,
 * linking each signature to the device that created it. Multiple devices can
 * have their own signatures for the same MCP server.
 *
 * The payload JSON is stored to preserve the exact bytes signed by the app,
 * ensuring signature verification works correctly without re-serialization.
 *
 * @property serverId Reference to the Local MCP server.
 * @property userDeviceId Reference to the user device that created the signature.
 * @property signature The Base64-encoded digital signature.
 * @property timestamp The creation time of the signature in epoch milliseconds.
 * @property nonce Unique identifier to prevent replay attacks.
 * @property payloadJson The exact raw JSON string that was signed.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
object LocalMCPServerSignaturesTable : CompositeIdTable("local_mcp_server_signatures") {
    val serverId = reference("server_id", LocalMCPServerTable, onDelete = ReferenceOption.CASCADE)
    val userDeviceId = reference("user_device_id", UserDevicesTable, onDelete = ReferenceOption.CASCADE)
    val signature = text("signature")
    val timestamp = long("timestamp")
    val nonce = varchar("nonce", 255)
    val payloadJson = text("payload_json")
    val createdAt = long("created_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
    val updatedAt = long("updated_at").clientDefault { Clock.System.now().toEpochMilliseconds() }

    override val primaryKey = PrimaryKey(serverId, userDeviceId)

    init {
        uniqueIndex("local_mcp_server_signatures_server_id_user_device_id_unique", serverId, userDeviceId)
    }
}
