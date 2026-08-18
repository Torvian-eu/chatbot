package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor

/**
 * Builds the tool-specific [AgentSpawnRequest] payload for a `spawn_agent` operator-tool call.
 *
 * The builder owns input parsing (extracting `subject`, `agent_role_name`, and `prompt` from the
 * LLM-provided arguments JSON), the user-scoped role lookup (`AgentRoleService.getRoleByName`), and
 * enforcement of the source role's spawn allow-list (`AgentRoleService.getRoleById`), producing the
 * typed payload that the operator executor serializes into the generic relay envelope. Keeping this
 * logic separate from the transport-focused [OperatorToolExecutor] makes the role resolution a pure,
 * unit-testable service.
 */
interface AgentSpawnRequestBuilder {

    /**
     * Builds a spawn request and enforces the source role's current allow-list.
     *
     * @param userId User owning the source and target roles.
     * @param requestingAgentRoleId Role making the request, obtained from the validated session rather
     *            than from model-controlled arguments.
     * @param toolCall Persisted operator call containing untrusted target arguments.
     * @return A validated request or a logical build error.
     */
    suspend fun build(
        userId: Long,
        requestingAgentRoleId: Long,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest>
}
