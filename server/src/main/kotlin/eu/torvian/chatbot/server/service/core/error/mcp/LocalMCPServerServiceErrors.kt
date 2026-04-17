package eu.torvian.chatbot.server.service.core.error.mcp

/**
 * Base interface for all Local MCP Server related service errors.
 */
sealed interface LocalMCPServerServiceError

/**
 * The requested Local MCP server does not exist.
 *
 * @property serverId Missing server identifier.
 */
data class LocalMCPServerNotFoundError(val serverId: Long) : LocalMCPServerServiceError

/**
 * The authenticated user is not authorized to access the requested Local MCP server.
 *
 * @property userId User identifier from authentication context.
 * @property serverId Target server identifier.
 */
data class LocalMCPServerUnauthorizedError(
    val userId: Long,
    val serverId: Long
) : LocalMCPServerServiceError

/**
 * The referenced worker assignment does not exist.
 *
 * @property workerId Missing worker identifier.
 */
data class LocalMCPServerWorkerNotFoundError(val workerId: Long) : LocalMCPServerServiceError

/**
 * The referenced worker belongs to a different user.
 *
 * @property userId Requesting user identifier.
 * @property workerId Referenced worker identifier.
 * @property workerOwnerUserId Actual worker owner identifier.
 */
data class LocalMCPServerWorkerOwnershipMismatchError(
    val userId: Long,
    val workerId: Long,
    val workerOwnerUserId: Long
) : LocalMCPServerServiceError

/**
 * A secret environment variable could not be persisted securely.
 *
 * @property variableKey Environment variable name that failed to persist.
 */
data class LocalMCPServerSecretStorageError(val variableKey: String) : LocalMCPServerServiceError

/**
 * A secret environment variable alias could not be resolved to plaintext.
 *
 * @property variableKey Environment variable name that failed to resolve.
 * @property alias Credential alias that failed to resolve.
 */
data class LocalMCPServerSecretResolutionError(
    val variableKey: String,
    val alias: String
) : LocalMCPServerServiceError

/**
 * Request payload failed server-side logical validation.
 *
 * @property reason Human-readable explanation of the invalid state.
 */
data class LocalMCPServerValidationError(val reason: String) : LocalMCPServerServiceError
