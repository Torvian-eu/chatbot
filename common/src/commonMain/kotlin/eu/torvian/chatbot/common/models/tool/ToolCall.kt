package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents a record of a tool invocation during a conversation.
 *
 * Tool calls are initiated by the LLM when it determines that calling a tool
 * would help answer the user's question. Each tool call is linked to an
 * assistant message and includes the input arguments, output results, and
 * execution metadata.
 *
 * @property id Unique identifier for this tool call record
 * @property messageId ID of the assistant message this tool call belongs to
 * @property toolDefinitionId Optional ID of the tool definition that was used.
 *                            This can be null if the LLM hallucinates a tool name.
 * @property toolName Name of the tool. This is directly stored and is always present,
 *                      even if it's a hallucinated name from the LLM.
 * @property toolCallId Optional unique ID from the LLM provider
 * @property input JSON string containing the arguments passed to the tool.
 *                 Stored as a string (not parsed JsonObject) to preserve invalid
 *                 JSON from the LLM, which is needed to generate proper error messages.
 *                 Null for parameterless function calls.
 * @property output Hybrid string containing the results returned by the tool.
 *                 This field is **hybrid**: it may be a JSON string (e.g. from `read_text_file`
 *                 returning file contents as JSON) or a plain text string (e.g. from `run_command`
 *                 returning stdout/stderr). Consumers should parse it as JSON when appropriate.
 *                 Null if the tool call is pending or if execution hasn't completed.
 * @property status Current execution status
 * @property errorMessage Error details if execution failed
 * @property denialReason Reason provided by user when denying tool call execution.
 *                        Only populated when status is USER_DENIED.
 * @property executedAt Timestamp when the tool was executed
 * @property durationMs Execution time in milliseconds (null if pending)
 * @property errorCode Optional machine-readable error code when [status] is [ToolCallStatus.ERROR].
 *                      Mirrors the code reported by the executor (e.g. a worker-side authorization
 *                      failure) and is surfaced back to the LLM alongside [errorMessage].
 * @property errorDetails Hybrid string containing optional structured diagnostics when
 *                       [status] is [ToolCallStatus.ERROR]. This field is **hybrid**: it may be
 *                       a JSON string (when set by tools like `run_command` with accumulated
 *                       validation errors in `errorDetails`) or a plain text string. Consumers
 *                       should parse it as JSON when appropriate to extract structured information.
 *                       Stored as a serialized string to preserve the original format without
 *                       imposing a fixed schema.
 */
@Serializable
data class ToolCall(
    val id: Long,
    val messageId: Long,
    val toolDefinitionId: Long?,
    val toolName: String,
    val toolCallId: String? = null,
    val input: String? = null,
    val output: String? = null,
    val status: ToolCallStatus,
    val errorMessage: String? = null,
    val denialReason: String? = null,
    val executedAt: Instant,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val errorDetails: String? = null
) {
    /**
     * Identifies tool calls whose model arguments failed JSON validation and must not be replayed.
     */
    companion object {
        /** Machine-readable marker for discarded, malformed model-emitted arguments. */
        const val INVALID_ARGUMENTS_ERROR_CODE: String = "INVALID_TOOL_ARGUMENTS_JSON"
    }

    /**
     * Indicates whether this record represents an assistant tool call safe to place in provider context.
     * Parameterless calls remain replayable because null input is valid unless this explicit marker is set.
     *
     * @return True when the call was not rejected for malformed arguments.
     */
    val isReplayableInLlmContext: Boolean
        get() = errorCode != INVALID_ARGUMENTS_ERROR_CODE
}

