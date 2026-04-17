package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.server.data.entities.LocalMCPSecretEnvironmentVariableReference
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Converts an Exposed row from `local_mcp_servers` to [LocalMCPServerEntity].
 *
 * Invalid JSON payloads are treated as empty lists to keep read paths robust while
 * preserving server availability for operator remediation.
 *
 * @receiver Result row containing `LocalMCPServerTable` columns.
 * @return Parsed [LocalMCPServerEntity].
 */
fun ResultRow.toLocalMCPServerEntity(): LocalMCPServerEntity = LocalMCPServerEntity(
    id = this[LocalMCPServerTable.id].value,
    userId = this[LocalMCPServerTable.userId].value,
    workerId = this[LocalMCPServerTable.workerId] ?: 0L,
    name = this[LocalMCPServerTable.name],
    description = this[LocalMCPServerTable.description],
    command = this[LocalMCPServerTable.command],
    arguments = runCatching { Json.decodeFromString<List<String>>(this[LocalMCPServerTable.argumentsJson]) }
        .getOrDefault(emptyList()),
    workingDirectory = this[LocalMCPServerTable.workingDirectory],
    isEnabled = this[LocalMCPServerTable.isEnabled],
    autoStartOnEnable = this[LocalMCPServerTable.autoStartOnEnable],
    autoStartOnLaunch = this[LocalMCPServerTable.autoStartOnLaunch],
    autoStopAfterInactivitySeconds = this[LocalMCPServerTable.autoStopAfterInactivitySeconds],
    toolNamePrefix = this[LocalMCPServerTable.toolNamePrefix],
    environmentVariables = runCatching {
        Json.decodeFromString<List<LocalMCPEnvironmentVariableDto>>(this[LocalMCPServerTable.environmentVariablesJson])
    }.getOrDefault(emptyList()),
    secretEnvironmentVariables = runCatching {
        Json.decodeFromString<List<LocalMCPSecretEnvironmentVariableReference>>(this[LocalMCPServerTable.secretEnvironmentVariablesJson])
    }.getOrDefault(emptyList()),
    createdAt = Instant.fromEpochMilliseconds(this[LocalMCPServerTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[LocalMCPServerTable.updatedAt])
)


