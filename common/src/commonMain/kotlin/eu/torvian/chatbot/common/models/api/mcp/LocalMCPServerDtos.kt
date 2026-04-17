package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Plaintext environment variable entry for Local MCP server configuration payloads.
 *
 * @property key Environment variable name.
 * @property value Environment variable value in plaintext for in-memory editing and runtime use.
 */
@Serializable
data class LocalMCPEnvironmentVariableDto(
    val key: String,
    val value: String
)

/**
 * Full Local MCP server configuration transferred between clients, server, and workers.
 *
 * Secret variable values are represented as plaintext in-memory and in authorized API responses.
 * Server persistence stores only secret aliases/references.
 *
 * @property id Unique server identifier.
 * @property userId Owning user identifier.
 * @property workerId Assigned worker identifier used for execution routing.
 * @property name User-facing display name.
 * @property description Optional description for operators.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory for process execution.
 * @property isEnabled Whether this server is enabled for use.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts on worker launch.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables, returned in plaintext for authorized consumers.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
@Serializable
data class LocalMCPServerDto(
    val id: Long,
    val userId: Long,
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolNamePrefix: String? = null,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Request payload for creating a fully configured Local MCP server.
 *
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional description for operators.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory for process execution.
 * @property isEnabled Whether this server is enabled for use.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts on worker launch.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables in plaintext for secure storage.
 */
@Serializable
data class CreateLocalMCPServerRequest(
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolNamePrefix: String? = null,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList()
)

/**
 * Request payload for updating a fully configured Local MCP server.
 *
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional description for operators.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory for process execution.
 * @property isEnabled Whether this server is enabled for use.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts on worker launch.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables in plaintext for secure storage.
 */
@Serializable
data class UpdateLocalMCPServerRequest(
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolNamePrefix: String? = null,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList()
)

/**
 * Response envelope for a single Local MCP server.
 *
 * @property server Local MCP server payload.
 */
@Serializable
data class LocalMCPServerResponse(
    val server: LocalMCPServerDto
)

/**
 * Response envelope for Local MCP server collections.
 *
 * @property servers Local MCP servers in the requested scope.
 */
@Serializable
data class LocalMCPServerListResponse(
    val servers: List<LocalMCPServerDto>
)

/**
 * Legacy request DTO retained during migration compatibility window.
 *
 * @property isEnabled The initial enabled/disabled state.
 */
@Deprecated("Use CreateLocalMCPServerRequest")
@Serializable
data class CreateServerRequest(
    val isEnabled: Boolean
)

/**
 * Legacy response DTO retained during migration compatibility window.
 *
 * @property id Generated server identifier.
 * @property userId Owning user identifier.
 * @property isEnabled Enabled/disabled state.
 */
@Deprecated("Use LocalMCPServerResponse")
@Serializable
data class CreateServerResponse(
    val id: Long,
    val userId: Long,
    val isEnabled: Boolean
)

/**
 * Legacy request DTO retained during migration compatibility window.
 *
 * @property isEnabled The new enabled/disabled state.
 */
@Deprecated("Use UpdateLocalMCPServerRequest")
@Serializable
data class SetServerEnabledRequest(
    val isEnabled: Boolean
)

/**
 * Legacy response DTO retained during migration compatibility window.
 *
 * @property ids List of server IDs owned by the user.
 * @property userId User identifier.
 */
@Deprecated("Use LocalMCPServerListResponse")
@Serializable
data class ServerIdsResponse(
    val ids: List<Long>,
    val userId: Long
)
