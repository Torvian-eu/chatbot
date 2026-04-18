package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

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