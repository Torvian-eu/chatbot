package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.getOrElse
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.service.llm.ReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.outputLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.sanitizeReasoningItems
import eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Runs one non-streaming assistant iteration for the shared turn loop: the full-response LLM call, the
 * truncation cut-off, and the persistence of the assistant message (including the empty failed row of a rejected
 * call).
 *
 * The step returns `null` on every path that ends the turn — a failed call, a response cut off at the character
 * cap (which also suppresses its untrustworthy tool calls), a response whose calls were dropped by the per-step or
 * argument cap, and a completed response without tool calls. Only the calls that survive the caps are handed back
 * (under the argument cap the clipped calls alone are dropped), and reaching the turn's iteration bound flags the
 * message while still handing its calls over, so the loop executes them before it ends the turn.
 *
 * @property llmApiClient Client used for the full-response LLM call and its error classification.
 * @property conversationTurnPersistence Collaborator that owns the assistant-message persistence workflow of the
 *            iteration (successful row, failed row and the reasoning items attached to it).
 * @property reasoningCapabilityRecorder Collaborator that records a model's reasoning mode (encrypted vs
 *            plaintext) from observed reasoning items, used to adapt reasoning replay across model switches.
 */
internal class NonStreamingAssistantStepRunner(
    private val llmApiClient: LLMApiClient,
    private val conversationTurnPersistence: ConversationTurnPersistence,
    private val reasoningCapabilityRecorder: ReasoningCapabilityRecorder
) {
    /** Logger used for non-streaming step diagnostics (call failures, discarded outputs, truncation). */
    private val logger: Logger = LogManager.getLogger(NonStreamingAssistantStepRunner::class.java)

    /**
     * Executes one non-streaming assistant iteration.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param currentContext Current raw conversation context.
     * @param parentMessageId Parent under which the next assistant message should be persisted.
     * @param isLastToolCallingIteration Whether the orchestrator's tool-calling loop may not run another
     *        iteration after this step. A tool-calling response then cannot be followed up, so its message records
     *        the iteration-limit failure while its calls (which no cap dropped) are still executed; the turn ends
     *        once they have run.
     * @param emit Sink used to publish lifecycle events.
     * @return Assistant-step outcome when tool execution should continue, or `null` when the turn is finished.
     */
    internal suspend fun run(
        request: ConversationTurnRequest,
        currentContext: List<RawChatMessage>,
        parentMessageId: Long,
        isLastToolCallingIteration: Boolean,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): AssistantStepOutcome? {
        val llmCompletionResult = run {
            llmApiClient.completeChat(
                messages = currentContext,
                modelConfig = request.llmConfig.model,
                provider = request.llmConfig.provider,
                settings = request.llmConfig.settings,
                apiKey = request.llmConfig.apiKey,
                tools = request.llmConfig.tools,
                systemMessage = request.llmConfig.systemMessage.takeIf { it.isNotBlank() }
            )
        }.getOrElse { error ->
            logger.error("LLM API call failed for session ${request.session.id}: $error")
            // A failed call still leaves a row: an empty failed message records that a response was attempted and
            // why it failed, so the transcript keeps a trace of it.
            persistFailedAssistantStep(request, parentMessageId, error, emit)
            return null
        }

        logger.info("LLM API call successful for session ${request.session.id}")

        // Record the model's reasoning mode (encrypted vs plaintext) from the observed reasoning items so
        // later replays can adapt what is sent to this model. Detection is a cheap, one-time write.
        reasoningCapabilityRecorder.record(request.llmConfig.model, llmCompletionResult.reasoningItems)

        val choice = llmCompletionResult.choices.firstOrNull() ?: run {
            logger.error("LLM API returned successful response with no choices for session ${request.session.id}")
            // A successful status without a completion choice is a provider-response failure, so it gets the
            // same empty failed row as a rejected call instead of only a turn-level error.
            persistFailedAssistantStep(
                request = request,
                parentMessageId = parentMessageId,
                llmError = LLMCompletionError.InvalidResponseError(
                    "LLM API returned success but no completion choices."
                ),
                emit = emit
            )
            return null
        }

        val originalContent = choice.content ?: ""
        // The character cap is enforced on the persisted text, and reaching it is a failure state rather than a
        // normal completion with an in-content notice (see below).
        val contentTruncated = originalContent.length > ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS
        val content = if (contentTruncated) {
            originalContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        } else {
            originalContent
        }
        // The response's tool calls are bounded by the same caps as the streamed ones, and the caps are checked
        // before the row is written so the message can carry the failure in the very statement that creates it.
        // A call refused by the per-step cap counts as dropped; a call whose payload exceeds the argument cap
        // counts as clipped (a cut argument is no longer the input the model sent).
        val requestedToolCallCount = choice.toolCalls?.size ?: 0
        val droppedToolCallCount =
            (requestedToolCallCount - ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP).coerceAtLeast(0)
        // Calls whose argument exceeds the cap are refused individually: a cut payload is no longer the input the
        // model sent, while every other call of the response is complete on its own and still valid to run.
        val clippedToolCallArgumentCount = choice.toolCalls
            .orEmpty()
            .count { toolCall ->
                (toolCall.arguments?.length ?: 0) > ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
            }
        // Whether the response is a tool-calling step, i.e. whether the loop has anything to do with it.
        val requestsToolCalls = choice.finishReason == "tool_calls" && !choice.toolCalls.isNullOrEmpty()
        // Assistant text cut at the cap is a failure rather than a normal completion, and it is the only limit
        // that also suppresses tool execution (a response cut off mid-generation is untrustworthy input).
        val contentFailure = if (contentTruncated) {
            outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        } else {
            null
        }
        // The tool-call limits have their own execution policies: the calls of a step over the per-step cap are
        // dropped as a whole, the argument cap drops only the calls whose argument exceeded it, and the iteration
        // bound keeps every call. Only a limit that still leaves calls to run flags the message and asks the loop
        // to end the turn after them (see AssistantStepOutcome.endTurnAfterToolCalls).
        val toolCallFailure = ConversationTurnLimits.toolCallLimitFailure(
            droppedToolCallCount = droppedToolCallCount,
            clippedToolCallArgumentCount = clippedToolCallArgumentCount,
            isLastToolCallingIteration = isLastToolCallingIteration,
            requestsToolCalls = requestsToolCalls
        )
        // Sanitize once before the items enter persistence or the follow-up tool-loop context.
        val sanitizedReasoningItems = llmCompletionResult.reasoningItems?.let(::sanitizeReasoningItems)
        val persistedAssistantMessage = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = content,
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings,
            agentRoleId = request.session.agentRoleId,
            reasoningItems = sanitizedReasoningItems,
            completion = contentFailure ?: toolCallFailure ?: AssistantMessageCompletionState.Completed
        )
        emit(
            ConversationTurnEvent.AssistantMessageSaved(
                persistedAssistantMessage.assistantMessage,
                persistedAssistantMessage.updatedParentMessage
            )
        )
        val assistantMessage = persistedAssistantMessage.assistantMessage

        // A truncated response must never drive tool execution. The turn therefore ends here — before processTurn
        // can persist pending tool calls or execute them — and only the count of the discarded requests is logged:
        // no tool-call row is written, nothing is executed, so no approval prompt or tool badge can appear below a
        // failed message.
        if (contentFailure != null) {
            if (requestedToolCallCount > 0) {
                logger.warn(
                    "Discarding $requestedToolCallCount tool call request(s) for session ${request.session.id}: " +
                        "${contentFailure.errorMessage} (${contentFailure.errorCode})"
                )
            }
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        // The caps decide which calls of this step may run: a response that asks for more calls than the per-step
        // cap allows is not acted upon at all (a batch that size means something went wrong), while a call whose
        // argument exceeds the argument cap is dropped on its own — the payload the cap would have to cut is no
        // longer the input the model sent, but the remaining calls of the response are untouched by it, so they are
        // handed over in their original order (dropping a call leaves the calls after it runnable).
        val executableToolCalls = choice.toolCalls.orEmpty().let { requestedToolCalls ->
            if (droppedToolCallCount > 0) {
                emptyList()
            } else {
                requestedToolCalls.filter { toolCall ->
                    (toolCall.arguments?.length ?: 0) <= ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
                }
            }
        }

        if (!requestsToolCalls || executableToolCalls.isEmpty()) {
            // No call of this step may run — the caps refused all of them, the response was a plain answer, or it
            // did not finish on tool calls — so the turn ends here without touching the tool layer.
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        if (toolCallFailure != null) {
            logToolCallLimitFailure(
                request = request,
                assistantMessageId = assistantMessage.id,
                failure = toolCallFailure,
                toolCallCount = executableToolCalls.size,
                droppedToolCallCount = droppedToolCallCount,
                clippedToolCallArgumentCount = clippedToolCallArgumentCount
            )
        }

        return AssistantStepOutcome(
            assistantMessage = assistantMessage,
            toolCallRequests = executableToolCalls,
            reasoningItems = sanitizedReasoningItems,
            // Reaching the iteration bound made this the turn's last step: the calls run, then the loop ends the
            // turn (the per-step and argument caps drop calls instead and never reach this outcome).
            endTurnAfterToolCalls = toolCallFailure != null
        )
    }

    /**
     * Logs the tool-call limit a step reached, together with the counts that made it reach the limit.
     *
     * The log is what tells an operator why a message carries a failure reason, how much of the batch ran and how
     * much was refused: the cap-specific counts distinguish the per-step cap (calls refused as a whole, none of
     * which may run) from the argument cap (calls whose payload had to be cut, every one of which is refused while
     * the others of the same step still run).
     *
     * @param request Immutable input bundle of the turn, used for the session id.
     * @param assistantMessageId Message that carries the failure state.
     * @param failure Failure state recorded on the message, naming the limit that was hit.
     * @param toolCallCount Tool calls of the step that are still handed to the loop for execution.
     * @param droppedToolCallCount Tool calls the per-step cap refused as a whole.
     * @param clippedToolCallArgumentCount Tool calls whose argument payload the argument cap had to cut.
     */
    private fun logToolCallLimitFailure(
        request: ConversationTurnRequest,
        assistantMessageId: Long,
        failure: AssistantMessageCompletionState,
        toolCallCount: Int,
        droppedToolCallCount: Int,
        clippedToolCallArgumentCount: Int
    ) {
        logger.warn(
            "Tool call limit reached for session ${request.session.id}, assistant message $assistantMessageId: " +
                "${failure.errorMessage} (${failure.errorCode}; tool calls refused by the per-step cap: " +
                "$droppedToolCallCount, tool calls with clipped arguments: $clippedToolCallArgumentCount). " +
                "Executing the $toolCallCount tool call(s) of the step anyway."
        )
    }

    /**
     * Persists an empty assistant message carrying the failure that ended a non-streaming iteration.
     *
     * The row is created with empty content because a failed full-response call produced no text, and it is
     * emitted before the transient error notification so the client can render the notice even when the user
     * dismisses the notification. The turn is closed with [ConversationTurnEvent.TurnCompleted].
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param parentMessageId Parent under which the failed assistant message should be persisted.
     * @param llmError Failure reported by the LLM client, mapped to a persisted code and bounded reason.
     * @param emit Sink used to publish lifecycle events.
     */
    private suspend fun persistFailedAssistantStep(
        request: ConversationTurnRequest,
        parentMessageId: Long,
        llmError: LLMCompletionError,
        emit: suspend (ConversationTurnEvent) -> Unit
    ) {
        val savedFailedStep = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings,
            agentRoleId = request.session.agentRoleId,
            reasoningItems = null,
            completion = llmError.toAssistantMessageCompletionState()
        )
        emit(
            ConversationTurnEvent.AssistantMessageSaved(
                savedFailedStep.assistantMessage,
                savedFailedStep.updatedParentMessage
            )
        )
        emit(ConversationTurnEvent.ExternalServiceError(llmError))
        emit(ConversationTurnEvent.TurnCompleted)
    }
}
