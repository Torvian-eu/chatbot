package eu.torvian.chatbot.server.service.core.error.mcp

/**
 * Base interface for runtime-control failures for Local MCP server operations.
 */
sealed interface LocalMCPRuntimeControlError

/**
 * The runtime-control target server does not exist.
 *
 * @property serverId Missing server identifier.
 */
data class LocalMCPRuntimeControlServerNotFoundError(
    val serverId: Long
) : LocalMCPRuntimeControlError

/**
 * The authenticated user is not authorized to control the target server.
 *
 * @property userId Authenticated user identifier.
 * @property serverId Target server identifier.
 */
data class LocalMCPRuntimeControlUnauthorizedError(
    val userId: Long,
    val serverId: Long
) : LocalMCPRuntimeControlError

/**
 * Runtime control is currently unavailable for the target server.
 *
 * @property serverId Target server identifier.
 * @property reason Human-readable unavailability reason.
 */
data class LocalMCPRuntimeControlRuntimeUnavailableError(
    val serverId: Long,
    val reason: String
) : LocalMCPRuntimeControlError

/**
 * An unexpected runtime-control failure occurred.
 *
 * @property message Human-readable diagnostic message.
 */
data class LocalMCPRuntimeControlInternalError(
    val message: String
) : LocalMCPRuntimeControlError

