package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor

/**
 * Builds the tool-specific [AgentSpawnRequest] payload for a `spawn_agent` operator-tool call.
 *
 * The builder owns input parsing (extracting `subject`, `agent_role_name`, and `prompt` from the LLM-provided
 * arguments JSON) and the user-scoped role lookup (`AgentRoleService.getRoleByName`), producing the
 * typed payload that the operato`r executor serializes into the generic relay envelope. Keeping this
 * logic separate from the transport-focused [OperatorToolExecutor] makes the role resolution a pure,
 * unit-testable service.
 */
interface AgentSpawnRequestBuilder {

    /**
     * Builds an [AgentSpawnRequest] for the given operator tool call.
     *
     * @param userId The spawning user; the requested role must be owned by this user.
     * @param toolCall The persisted tool call whose `input` carries the `subject`, `agent_role_name`,
     *            and `prompt` parameters and whose [ToolCall.id] becomes the request's correlation key.
     * @return Either a [SpawnRequestBuildError] (surfaced to the LLM as a tool error result) or the
     *         typed [AgentSpawnRequest].
     */
    suspend fun build(userId: Long, toolCall: ToolCall): Either<SpawnRequestBuildError, AgentSpawnRequest>
}
