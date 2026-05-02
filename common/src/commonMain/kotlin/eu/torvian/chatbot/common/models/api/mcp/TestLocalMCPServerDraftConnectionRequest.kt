package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Request payload for testing a non-persisted Local MCP server configuration.
 *
 * This payload contains all necessary fields to test a draft server config without requiring persistence.
 *
 * @property workerId Assigned worker identifier used for execution routing.
 * @property name User-facing display name.
 * @property command Process command used to start the MCP server.
 * @property arguments Process argument list.
 * @property workingDirectory Optional working directory for process execution.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables, returned in plaintext for runtime use.
 */
@Serializable
data class TestLocalMCPServerDraftConnectionRequest(
    val workerId: Long,
    val name: String,
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList()
)
