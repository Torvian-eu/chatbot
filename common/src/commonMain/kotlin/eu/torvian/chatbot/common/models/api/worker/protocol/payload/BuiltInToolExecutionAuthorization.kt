package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable

/**
 * App-signed authorization payload for one built-in tool execution request.
 *
 * The app signs this exact DTO and sends it together with the detached
 * [eu.torvian.chatbot.common.security.SignedRequest] so the worker can verify that the
 * user-approved execution intent still matches the relayed execution command.
 *
 * @property toolCallId Persisted tool-call record that the authorization applies to.
 * @property sessionId Session that owns the approved tool call.
 * @property messageId Assistant message that owns the tool call.
 * @property toolDefinitionId Persisted built-in tool definition approved by the user.
 * @property toolName Public tool name that appeared in the chat tool call (e.g. `project1_read_text_file`).
 * @property workerId Worker that should execute the built-in tool.
 * @property builtInToolName Unprefixed built-in tool name (e.g. `read_text_file`).
 * @property input Exact JSON argument string approved by the app, preserved byte-for-byte.
 * @property approved Whether the app authorized execution.
 * @property denialReason Optional denial reason supplied by the app when execution was rejected.
 */
@Serializable
data class BuiltInToolExecutionAuthorization(
    val toolCallId: Long,
    val sessionId: Long,
    val messageId: Long,
    val toolDefinitionId: Long,
    val toolName: String,
    val workerId: Long,
    val builtInToolName: String,
    val input: String?,
    val approved: Boolean,
    val denialReason: String? = null
)
