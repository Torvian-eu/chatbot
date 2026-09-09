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
 * The builder owns input parsing (extracting `subject`, `agent_role_name`, and `prompt` from the
 * LLM-provided arguments JSON), the user-scoped role lookup (`AgentRoleService.getRoleByName`), and
 * enforcement of the source role's spawn allow-list (`AgentRoleService.getRoleById`), producing the
 * typed payload that the operator executor serializes into the generic relay envelope. The payload
 * includes the calling session's project so the spawned session can be scoped to the same project
 * before the role is attached — spawns are strictly same-scope (a project-attached session may only
 * spawn roles within that project, a project-less session only unassociated roles), which keeps the
 * operator from having to guess a legal project. Keeping this logic separate from the
 * transport-focused [OperatorToolExecutor] makes the role resolution a pure, unit-testable service.
 */
interface AgentSpawnRequestBuilder {

    /**
     * Builds a spawn request and enforces the source role's current allow-list.
     *
     * The name-based target lookup is scoped by the context's project: a session attached to a
     * project resolves only roles within that project, an unassociated session only unassociated
     * roles, so a project-selected spawn never falls back to (and never accidentally matches) a
     * role of another scope.
     *
     * @param context Caller identity plus the turn's session/role/project context; see
     *            [ToolCallExecutionContext].
     * @param toolCall Persisted operator call containing untrusted target arguments.
     * @return A validated request or a logical build error.
     */
    suspend fun build(
        context: ToolCallExecutionContext,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest>
}