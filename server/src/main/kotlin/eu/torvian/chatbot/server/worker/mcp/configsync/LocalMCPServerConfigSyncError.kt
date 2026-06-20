package eu.torvian.chatbot.server.worker.mcp.configsync

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.toApiError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.toApiError

/**
 * Error returned while orchestrating Local MCP persistence together with worker cache synchronization.
 */
sealed interface LocalMCPServerConfigSyncError {
    /**
     * Indicates that the server-side persistence operation failed before worker synchronization completed.
     *
     * @property error Underlying Local MCP persistence error.
     */
    data class ServerOperationFailed(
        val error: LocalMCPServerServiceError
    ) : LocalMCPServerConfigSyncError

    /**
     * Indicates that persistence succeeded but worker synchronization failed.
     *
     * @property error Underlying worker-sync failure.
     */
    data class WorkerSyncFailed(
        val error: LocalMCPRuntimeCommandDispatchError
    ) : LocalMCPServerConfigSyncError

    /**
     * Indicates that worker synchronization failed and the attempted compensation also failed.
     *
     * @property operation Human-readable name of the compensation step that failed.
     * @property syncError Original worker-sync failure that triggered compensation.
     * @property compensationError Persistence failure encountered while compensating.
     */
    data class CompensationFailed(
        val operation: String,
        val syncError: LocalMCPRuntimeCommandDispatchError,
        val compensationError: LocalMCPServerServiceError
    ) : LocalMCPServerConfigSyncError
}

/**
 * Converts a Local MCP config-sync orchestration error to an API error.
 *
 * @receiver Orchestration error to convert.
 * @return Structured API error for route responses.
 */
fun LocalMCPServerConfigSyncError.toApiError(): ApiError = when (this) {
    is LocalMCPServerConfigSyncError.ServerOperationFailed -> error.toApiError()
    is LocalMCPServerConfigSyncError.WorkerSyncFailed -> error.toApiError()
    is LocalMCPServerConfigSyncError.CompensationFailed -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Failed to restore Local MCP server state after worker sync failure",
        "operation" to operation,
        "syncErrorType" to syncError.javaClass.simpleName,
        "compensationErrorType" to compensationError.javaClass.simpleName
    )
}