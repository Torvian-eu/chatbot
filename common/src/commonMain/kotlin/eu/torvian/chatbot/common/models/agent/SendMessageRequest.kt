package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable

/**
 * Tool-specific payload the server sends to the operator inside
 * `OperatorToolExecutionRequested` for a `send_message` call.
 *
 * `send_message` injects a user [message] into an existing chat session identified by
 * [chatSessionId] and — in wait mode — returns that session's last assistant message. Unlike
 * [AgentSpawnRequest] there is no role, subject, or conversation: the target session already exists
 * and owns its role, so the operator only needs the session id, the message text, and the shared
 * [OperatorToolMode] to drive the target turn exactly like the spawned first turn.
 *
 * The server validates before relaying that the target session exists and is owned by the calling
 * user (see the server-side `SendMessageRequestBuilder`); sendability (a resolvable
 * role/model/settings profile) is left to the operator at runtime.
 *
 * @property chatSessionId Id of the target chat session to inject the message into. Must be owned
 *            by the current user (server-validated before the relay).
 * @property message Message text to inject as a user turn in the target session. Non-blank by
 *            builder contract.
 * @property mode How the operator reports the outcome: [OperatorToolMode.WAIT_FOR_RESPONSE] returns
 *            the target session's last assistant message; [OperatorToolMode.FIRE_AND_FORGET] returns
 *            a plain success notification immediately after the turn starts.
 * @property toolCallId The persisted `ToolCall.id`; the correlation key echoed back in
 *            `ToolExecutionResult`.
 */
@Serializable
data class SendMessageRequest(
    val chatSessionId: Long,
    val message: String,
    val mode: OperatorToolMode = OperatorToolMode.WAIT_FOR_RESPONSE,
    val toolCallId: Long
)