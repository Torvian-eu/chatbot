package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Logical error model returned by server-side worker MCP runtime command orchestration.
 */
sealed interface LocalMCPRuntimeCommandDispatchError {
    /**
     * Worker dispatch failed before a successful completed lifecycle result was available.
     *
     * @property error Dispatch-layer failure describing rejection, timeout, disconnect, or malformed lifecycle data.
     */
    data class DispatchFailed(
        val error: WorkerCommandDispatchError
    ) : LocalMCPRuntimeCommandDispatchError

    /**
     * Worker command payload mapping failed while preparing or decoding MCP control data.
     *
     * @property commandType Worker protocol command type tied to the mapping operation.
     * @property details Human-readable mapping failure details.
     */
    data class InvalidPayload(
        val commandType: String,
        val details: String
    ) : LocalMCPRuntimeCommandDispatchError

    /**
     * Worker completed a command with `error` status and provided typed error payload details.
     *
     * @property commandType Worker protocol command type tied to the failed runtime operation.
     * @property code Stable worker/runtime failure code.
     * @property message Human-readable runtime failure message.
     * @property details Optional additional worker/runtime diagnostics.
     */
    data class CommandFailed(
        val commandType: String,
        val code: String,
        val message: String,
        val details: String?
    ) : LocalMCPRuntimeCommandDispatchError

    /**
     * Worker completed command dispatch with an unsupported terminal result status.
     *
     * @property commandType Worker protocol command type tied to the result payload.
     * @property status Terminal command status emitted by the worker.
     */
    data class UnexpectedResultStatus(
        val commandType: String,
        val status: String
    ) : LocalMCPRuntimeCommandDispatchError
}


