package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persisted Local MCP server row materialized from server storage.
 *
 * Secret environment variables are represented by alias references, not raw values.
 *
 * @property id Unique server identifier.
 * @property userId Owning user identifier.
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional descriptive text.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory used for process execution.
 * @property isEnabled Whether this server is enabled.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts when worker launches.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during tool discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variable alias references.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class LocalMCPServerEntity(
    val id: Long,
    val userId: Long,
    val workerId: Long,
    val name: String,
    val description: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val isEnabled: Boolean,
    val autoStartOnEnable: Boolean,
    val autoStartOnLaunch: Boolean,
    val autoStopAfterInactivitySeconds: Int?,
    val toolNamePrefix: String?,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto>,
    val secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference>,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Payload used by DAO create operations for Local MCP servers.
 *
 * @property userId Owning user identifier.
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional descriptive text.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory used for process execution.
 * @property isEnabled Whether this server is enabled.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts when worker launches.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during tool discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variable alias references.
 */
data class CreateLocalMCPServerEntity(
    val userId: Long,
    val workerId: Long,
    val name: String,
    val description: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val isEnabled: Boolean,
    val autoStartOnEnable: Boolean,
    val autoStartOnLaunch: Boolean,
    val autoStopAfterInactivitySeconds: Int?,
    val toolNamePrefix: String?,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto>,
    val secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference>
)

/**
 * Payload used by DAO update operations for Local MCP servers.
 *
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional descriptive text.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory used for process execution.
 * @property isEnabled Whether this server is enabled.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts when worker launches.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during tool discovery.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variable alias references.
 */
data class UpdateLocalMCPServerEntity(
    val workerId: Long,
    val name: String,
    val description: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val isEnabled: Boolean,
    val autoStartOnEnable: Boolean,
    val autoStartOnLaunch: Boolean,
    val autoStopAfterInactivitySeconds: Int?,
    val toolNamePrefix: String?,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto>,
    val secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference>
)

/**
 * Secret environment variable reference persisted in Local MCP server JSON columns.
 *
 * @property key Environment variable name.
 * @property alias Credential-manager alias used to resolve the secret value.
 */
@Serializable
data class LocalMCPSecretEnvironmentVariableReference(
    val key: String,
    val alias: String
)

