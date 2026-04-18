package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

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