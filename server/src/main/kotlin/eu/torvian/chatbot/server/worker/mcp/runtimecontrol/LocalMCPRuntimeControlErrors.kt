package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

/**
 * Sealed class for runtime-control failures for Local MCP server operations.
 */
sealed class LocalMCPRuntimeControlError {
    /**
     * The runtime-control target server does not exist.
     *
     * @property serverId Missing server identifier.
     */
    data class ServerNotFoundError(
        val serverId: Long
    ) : LocalMCPRuntimeControlError()

    /**
     * The authenticated user is not authorized to control the target server.
     *
     * @property userId Authenticated user identifier.
     * @property serverId Target server identifier.
     */
    data class UnauthorizedError(
        val userId: Long,
        val serverId: Long
    ) : LocalMCPRuntimeControlError()

    /**
     * Runtime control is currently unavailable for the target server.
     *
     * @property serverId Target server identifier.
     * @property reason Human-readable unavailability reason.
     */
    data class RuntimeUnavailableError(
        val serverId: Long,
        val reason: String
    ) : LocalMCPRuntimeControlError()

    /**
     * Draft runtime control is unavailable for the requested worker.
     *
     * @property workerId Requested worker identifier.
     * @property reason Human-readable unavailability reason.
     */
    data class DraftRuntimeUnavailableError(
        val workerId: Long,
        val reason: String
    ) : LocalMCPRuntimeControlError()

    /**
     * An unexpected runtime-control failure occurred.
     *
     * @property message Human-readable diagnostic message.
     */
    data class InternalError(
        val message: String
    ) : LocalMCPRuntimeControlError()
}

