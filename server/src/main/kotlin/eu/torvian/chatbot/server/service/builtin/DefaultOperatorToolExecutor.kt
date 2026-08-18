package eu.torvian.chatbot.server.service.builtin

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.agent.AgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import eu.torvian.chatbot.server.service.core.toolcall.OperatorToolExecutionResult
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Default implementation of [OperatorToolExecutor].
 *
 * For a `spawn_agent` call the executor:
 *
 * 1. Builds the typed [AgentSpawnRequest] via [AgentSpawnRequestBuilder] (input parsing, user-scoped
 *    role lookup, and the source role's spawn allow-list). A build failure is mapped to a tool-level
 *    ERROR result, so the LLM hears a clear message instead of the turn crashing.
 * 2. Serializes the typed payload into the generic relay envelope (`toolName` = the tool call's
 *    [ToolCall.toolName], which for operator tools is unique per user) and emits
 *    [ToolCallExecutionEvent.OperatorToolExecutionRequested].
 * 3. Awaits the [OperatorToolExecutionResult] whose [OperatorToolExecutionResult.toolCallId] matches
 *    the persisted tool call on the dedicated `operatorToolResultFlow`.
 * 4. Returns the terminal [ToolCall] (SUCCESS/ERROR) so the orchestrator can persist and relay it.
 *
 * The wait is cooperative: when the surrounding coroutine is cancelled (socket close, turn stop) the
 * suspension simply unwinds and the orchestrator's cleanup finalizes the call as CANCELLED.
 *
 * @property agentSpawnRequestBuilder Builds the typed spawn payload (role-by-name + ownership +
 *            source-role allow-list).
 * @property json JSON codec used to serialize the typed payload into the envelope.
 */
class DefaultOperatorToolExecutor(
    private val agentSpawnRequestBuilder: AgentSpawnRequestBuilder,
    private val json: Json
) : OperatorToolExecutor {

    companion object {
        /** Logger used for operator-tool execution diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultOperatorToolExecutor::class.java)
    }

    override suspend fun executeTool(
        userId: Long,
        requestingAgentRoleId: Long,
        toolCall: ToolCall,
        emitEvent: suspend (ToolCallExecutionEvent) -> Unit,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>
    ): ToolCall {
        val startTime = Clock.System.now()

        // Only `spawn_agent` is implemented so far. Any other operator-tool name reaching this
        // executor is a misconfiguration (the orchestrator dispatches operator tools here by
        // definition), so fail fast with a readable tool-level error instead of emitting a relay
        // event the operator would not recognize.
        if (toolCall.toolName != OperatorToolCatalog.SPAWN_AGENT_NAME) {
            logger.warn(
                "Unsupported operator tool '${toolCall.toolName}' for tool call ${toolCall.id}"
            )
            return toolCall.toErrorResult(
                errorMessage = "Unsupported operator tool '${toolCall.toolName}'. " +
                    "Only '${OperatorToolCatalog.SPAWN_AGENT_NAME}' is supported.",
                startTime = startTime
            )
        }

        val payload = agentSpawnRequestBuilder.build(userId, requestingAgentRoleId, toolCall)
            .getOrElse { buildError ->
                logger.warn("spawn_agent payload build failed for tool call ${toolCall.id}: $buildError")
                return toolCall.toErrorResult(
                    errorMessage = buildError.toUserMessage(),
                    startTime = startTime
                )
            }

        val payloadJson = runCatching {
            json.encodeToString(AgentSpawnRequest.serializer(), payload)
        }.getOrElse { error ->
            logger.error("Failed to serialize AgentSpawnRequest for tool call ${toolCall.id}", error)
            return toolCall.toErrorResult(
                errorMessage = "Failed to serialize the spawn request: ${error.message}",
                startTime = startTime
            )
        }

        emitEvent(
            ToolCallExecutionEvent.OperatorToolExecutionRequested(
                toolCallId = toolCall.id,
                toolName = toolCall.toolName,
                payloadJson = payloadJson
            )
        )

        // Await the operator's result correlated by tool call id. Cancellation (socket close / turn
        // stop) unwinds this suspension; the orchestrator's finally-block finalizes the call.
        val result = awaitResult(toolCall.id, operatorToolResultFlow)

        val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
        return if (result.isError) {
            toolCall.copy(
                status = ToolCallStatus.ERROR,
                output = result.output,
                errorMessage = result.errorMessage ?: "Operator tool execution failed.",
                durationMs = durationMs
            )
        } else {
            toolCall.copy(
                status = ToolCallStatus.SUCCESS,
                output = result.output,
                errorMessage = null,
                durationMs = durationMs
            )
        }
    }

    /**
     * Suspends until the operator returns a result for [toolCallId] on [operatorToolResultFlow].
     *
     * @param toolCallId Correlation key echoed from the relay event.
     * @param operatorToolResultFlow Dedicated client→server result channel.
     * @return The matching [OperatorToolExecutionResult].
     */
    private suspend fun awaitResult(
        toolCallId: Long,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>
    ): OperatorToolExecutionResult = coroutineScope {
        operatorToolResultFlow.first { result -> result.toolCallId == toolCallId }
    }

    /**
     * Converts a [SpawnRequestBuildError] into a human-readable message for the calling LLM.
     *
     * @receiver The typed build failure.
     * @return A message the LLM can act on (e.g. "role 'x' not found").
     */
    private fun SpawnRequestBuildError.toUserMessage(): String = when (this) {
        is SpawnRequestBuildError.InvalidInput -> reason
        is SpawnRequestBuildError.RoleNotFound ->
            "Role '$roleName' not found. You may only spawn agent roles owned by the current user."
        is SpawnRequestBuildError.RoleNotAllowed ->
            "The current agent role is not permitted to spawn role '$roleName'."
    }

    /**
     * Builds a terminal ERROR [ToolCall] for a payload-build or serialization failure.
     *
     * @param errorMessage Human-readable error to surface to the LLM.
     * @param startTime Execution start instant used to compute duration.
     * @return The terminal [ToolCall] copy.
     */
    private fun ToolCall.toErrorResult(errorMessage: String, startTime: Instant): ToolCall = copy(
        status = ToolCallStatus.ERROR,
        output = null,
        errorMessage = errorMessage,
        durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
    )
}
