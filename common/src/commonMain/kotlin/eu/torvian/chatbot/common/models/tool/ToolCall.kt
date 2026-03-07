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
 * @property output JSON string containing the results returned by the tool.
 *                  Null if the tool call is pending or if execution hasn't completed.
 * @property status Current execution status
 * @property errorMessage Error details if execution failed
 * @property denialReason Reason provided by user when denying tool call execution.
 *                        Only populated when status is USER_DENIED.
 * @property executedAt Timestamp when the tool was executed
 * @property durationMs Execution time in milliseconds (null if pending)
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
    val durationMs: Long? = null
)

