package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable

/**
 * Tool-specific payload the server sends to the operator inside
 * `OperatorToolExecutionRequested` for a `spawn_agent` call.
 *
 * The request carries everything the operator needs to drive the spawned conversation headlessly:
 * the role to spawn (as the shared, serializable [AgentRoleDto] — the operator attaches it to a new
 * chat session), the requested session [subject], the optional [interactive] handoff flag, the
 * conversation to start (usually a single [AgentSpawnMessage.User] with the prompt), and the
 * persisted [toolCallId] used to correlate the operator's `ToolExecutionResult` back to the
 * originating tool call.
 *
 * [interactive] selects the spawn execution mode. Absent/`false` (default) preserves today's
 * summary-return contract: the operator aggregates the spawned turn's last assistant message and
 * returns it as the tool result. `true` selects handoff mode: the operator still creates the
 * session, attaches the role, and drives the first turn headlessly, but never aggregates a
 * summary — it completes the tool with empty output so the user can continue the spawned
 * conversation in the app and report the final result back manually.
 *
 * @property agentRoleToSpawn The user-owned agent role the spawned conversation will use. Carried as
 *            the wire DTO because the server maps its domain `AgentRole` → [AgentRoleDto] before
 *            sending; the DTO already carries everything the operator needs (role id to attach, plus
 *            resolved instructions and tool ids used at turn time).
 * @property subject User-facing subject used as the spawned session's name, after the operator adds
 *            its spawned-session prefix.
 * @property interactive Whether the spawn runs in interactive (handoff) mode. `false`/absent keeps
 *            the default summary-return behavior; `true` makes the tool complete with empty output
 *            after the first spawned turn so the user continues the conversation in the app and
 *            reports the result back manually.
 * @property operatorType Which operator drives the spawn; v1 always uses
 *            [OperatorType.CLIENT_APP], kept for forward compatibility with the background operator.
 * @property conversation The conversation to run in the spawned session. In practice a single
 *            [AgentSpawnMessage.User] item carrying the prompt.
 * @property toolCallId The persisted `ToolCall.id`; the correlation key echoed back in
 *            `ToolExecutionResult`.
 */
@Serializable
data class AgentSpawnRequest(
    val agentRoleToSpawn: AgentRoleDto,
    val subject: String,
    val interactive: Boolean = false,
    val operatorType: OperatorType = OperatorType.CLIENT_APP,
    val conversation: List<AgentSpawnMessage> = emptyList(),
    val toolCallId: Long
)
