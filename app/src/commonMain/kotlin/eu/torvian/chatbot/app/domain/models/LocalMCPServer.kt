package eu.torvian.chatbot.app.domain.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents the complete configuration for a Local MCP (Model Context Protocol) Server.
 *
 * Local MCP Servers are executable processes that run on the user's machine and communicate
 * via STDIO. They provide tools that can be discovered and executed by the LLM.
 *
 * This model contains the full configuration needed to launch and manage an MCP server process.
 * The configuration is stored client-side (Desktop, Android, WASM) using SQLDelight, with each
 * platform maintaining its own independent database.
 *
 * Environment variables are stored encrypted using the EncryptedSecretTable mechanism.
 *
 * @property id Server-generated unique identifier (from server API)
 * @property userId The user who owns this MCP server configuration
 * @property name Display name for the server (unique per user, enforced at application level)
 * @property description Optional description of what the server provides
 * @property command Executable command to launch the server (e.g., "java", "uv", "docker", "npx")
 * @property arguments Command-line arguments to pass to the executable
 * @property environmentVariables Environment variables to set before launching (decrypted in-memory representation)
 * @property workingDirectory Optional working directory for process execution
 * @property isEnabled Global enable/disable flag. If false, ALL tools from this server are unavailable.
 * @property autoStartOnEnable Auto-start the server when a tool is enabled for a session
 * @property autoStartOnLaunch Auto-start the server when the application launches
 * @property autoStopAfterInactivitySeconds Auto-stop after inactivity (null = use default 300s, 0 = never stop)
 * @property toolsEnabledByDefault Whether tools from this server are enabled by default for NEW chat sessions
 * @property createdAt Timestamp when the configuration was created
 * @property updatedAt Timestamp when the configuration was last updated
 */
@Serializable
data class LocalMCPServer(
    val id: Long,
    val userId: Long,
    val name: String,
    val description: String? = null,
    val command: String,
    val arguments: List<String>,
    val environmentVariables: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolsEnabledByDefault: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Returns the effective auto-stop inactivity duration in seconds.
     * - null: Use default (300 seconds = 5 minutes)
     * - 0: Never auto-stop
     * - positive value: Auto-stop after this many seconds
     */
    val effectiveAutoStopSeconds: Int
        get() = autoStopAfterInactivitySeconds ?: DEFAULT_AUTO_STOP_SECONDS

    /**
     * Returns whether the server should never auto-stop.
     */
    val neverAutoStop: Boolean
        get() = autoStopAfterInactivitySeconds == 0

    companion object {
        const val DEFAULT_AUTO_STOP_SECONDS = 300 // 5 minutes
    }
}
