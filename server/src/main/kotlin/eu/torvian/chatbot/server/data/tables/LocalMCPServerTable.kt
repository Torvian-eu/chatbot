package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import kotlin.time.Clock

/**
 * Server-side source-of-truth storage for Local MCP server configuration.
 *
 * Secret environment variable values are never stored directly in this table. Instead,
 * `secretEnvironmentVariablesJson` stores credential aliases resolved through CredentialManager.
 *
 * @property userId Owning user identifier.
 * @property workerId Assigned worker identifier.
 * @property name User-facing display name.
 * @property description Optional descriptive text.
 * @property command Process command used to start the MCP server.
 * @property argumentsJson JSON-encoded process argument list.
 * @property workingDirectory Optional working directory for process execution.
 * @property isEnabled Whether this server is enabled.
 * @property autoStartOnEnable Whether this server auto-starts when enabled.
 * @property autoStartOnLaunch Whether this server auto-starts on worker launch.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout for auto-stop behavior.
 * @property toolNamePrefix Optional tool-name prefix applied during discovery.
 * @property environmentVariablesJson JSON-encoded non-secret environment variables.
 * @property secretEnvironmentVariablesJson JSON-encoded secret environment variable aliases.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
object LocalMCPServerTable : LongIdTable("local_mcp_servers") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val workerId = long("worker_id").nullable().index()
    val name = varchar("name", 255).default("Legacy MCP Server")
    val description = text("description").nullable()
    val command = text("command").default("")
    val argumentsJson = text("arguments_json").default("[]")
    val workingDirectory = text("working_directory").nullable()
    val isEnabled = bool("is_enabled").default(true)
    val autoStartOnEnable = bool("auto_start_on_enable").default(false)
    val autoStartOnLaunch = bool("auto_start_on_launch").default(false)
    val autoStopAfterInactivitySeconds = integer("auto_stop_after_inactivity_seconds").nullable()
    val toolNamePrefix = varchar("tool_name_prefix", 255).nullable()
    val environmentVariablesJson = text("environment_variables_json").default("[]")
    val secretEnvironmentVariablesJson = text("secret_environment_variables_json").default("[]")
    val createdAt = long("created_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
    val updatedAt = long("updated_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
}

