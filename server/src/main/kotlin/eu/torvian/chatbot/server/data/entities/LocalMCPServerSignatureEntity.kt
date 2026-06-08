package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row in the signature metadata table for Local MCP servers.
 *
 * This entity stores signature information that links a Local MCP server configuration
 * to the device that signed it, enabling the worker to verify the request originated
 * from a trusted source.
 *
 * @property serverId The Local MCP server identifier this signature belongs to.
 * @property userDeviceId The user device identifier that created this signature.
 * @property signature The Base64-encoded digital signature.
 * @property timestamp The creation time of the signature, in epoch milliseconds.
 *   Used for replay/staleness checks.
 * @property nonce A unique per-signature random identifier.
 *   Used to prevent replay attacks.
 * @property payloadJson The exact raw JSON string that was signed.
 *   This preserves the original bytes for signature verification, avoiding
 *   re-serialization mismatches.
 * @property createdAt Timestamp when this signature record was created.
 * @property updatedAt Timestamp when this signature record was last updated.
 */
data class LocalMCPServerSignatureEntity(
    val serverId: Long,
    val userDeviceId: Long,
    val signature: String,
    val timestamp: Long,
    val nonce: String,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
