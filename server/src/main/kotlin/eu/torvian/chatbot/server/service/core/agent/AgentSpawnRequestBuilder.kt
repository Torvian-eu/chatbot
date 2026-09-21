package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor

/**
 * Builds the tool-specific [AgentSpawnRequest] payload for a `spawn_agent` operator-tool call.
 *
 * The builder owns input parsing (extracting `subject`, `agent_role_id`, and `prompt` from the
 * LLM-provided arguments JSON), the owner-scoped role lookup by id (`AgentRoleService.getRoleById`),
 * and enforcement of the source role's spawn allow-list, producing the typed payload that the
 * operator executor serializes into the generic relay envelope. The payload carries the project the
 * spawned session must be scoped to before the role is attached — the spawn target's own project, or
 * `null` for an unassociated target — so the operator never has to guess a legal project, whatever
 * the calling session's scope. Keeping this logic separate from the transport-focused
 * [OperatorToolExecutor] makes the role resolution a pure, unit-testable service.
 */
interface AgentSpawnRequestBuilder {

    /**
     * Builds a spawn request and enforces the source role's current allow-list.
     *
     * The target is resolved by its owner-scoped id, so an unknown or foreign id is not found while an
     * owned id missing from the source role's allow-list is not allowed; the target's project scope is
     * never compared, and the request carries that project as the spawned session's scope.
     *
     * @param context Caller identity plus the turn's session context; see
     *            [ToolCallExecutionContext].
     * @param toolCall Persisted operator call containing untrusted target arguments.
     * @return A validated request or a logical build error.
     */
    suspend fun build(
        context: ToolCallExecutionContext,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest>
}