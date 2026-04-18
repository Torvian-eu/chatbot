package eu.torvian.chatbot.app.domain.models

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest

/**
 * Converts this canonical [LocalMCPServerDto] into an update request payload.
 *
 * @receiver Canonical Local MCP server model whose mutable fields are sent to the server.
 * @return Update request compatible with the server-owned Local MCP CRUD contract.
 */
fun LocalMCPServerDto.toUpdateRequest(): UpdateLocalMCPServerRequest = UpdateLocalMCPServerRequest(
    workerId = workerId,
    name = name,
    description = description,
    command = command,
    arguments = arguments,
    workingDirectory = workingDirectory,
    isEnabled = isEnabled,
    autoStartOnEnable = autoStartOnEnable,
    autoStartOnLaunch = autoStartOnLaunch,
    autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
    toolNamePrefix = toolNamePrefix,
    environmentVariables = environmentVariables,
    secretEnvironmentVariables = secretEnvironmentVariables
)

