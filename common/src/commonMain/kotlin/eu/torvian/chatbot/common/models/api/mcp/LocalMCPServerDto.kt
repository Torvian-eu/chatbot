package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
) {

    /**
     * Effective auto-stop timeout in seconds.
     *
     * @return [autoStopAfterInactivitySeconds] or [Companion.DEFAULT_LOCAL_MCP_AUTO_STOP_SECONDS].
     */
    val effectiveAutoStopSeconds: Int
        get() = autoStopAfterInactivitySeconds ?: DEFAULT_LOCAL_MCP_AUTO_STOP_SECONDS

    /**
     * Indicates whether the server should never auto-stop.
     *
     * @return `true` when [autoStopAfterInactivitySeconds] is `0`.
     */
    val neverAutoStop: Boolean
        get() = autoStopAfterInactivitySeconds == 0

    companion object {
        /**
         * Default timeout applied when [LocalMCPServerDto.autoStopAfterInactivitySeconds] is null.
         */
        const val DEFAULT_LOCAL_MCP_AUTO_STOP_SECONDS: Int = 300
    }
}
