package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Server-side compensation snapshot for one Local MCP server and one signer identity.
 *
 * This snapshot is intentionally separate from [eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto]
 * because compensation must be able to represent "unsigned for signer X". The shared transport DTO loses the
 * signer identity when `signedRequest` is null, while compensation still needs that signer identity to reconcile
 * the single persisted signature row keyed by `(serverId, userDeviceId)`.
 *
 * @property server Previously persisted Local MCP server configuration.
 * @property signerId Client-side signer identifier whose signature row is being compensated.
 * @property signedRequest Previously persisted detached signature snapshot for this signer, or null when that
 * signer previously had no signature row.
 */
data class LocalMCPServerSignerSnapshot(
    val server: LocalMCPServerDto,
    val signerId: String,
    val signedRequest: SignedRequest?
)