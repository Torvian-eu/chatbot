package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.service.llm.ReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.outputLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.sanitizeReasoningItems
import eu.torvian.chatbot.server.service.llm.streamInterruptedCompletionState
import eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.unexpectedFailureCompletionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Runs one streaming assistant iteration for the shared turn loop: placeholder creation, streamed content and
 * reasoning accumulation, and the exactly-once terminal state of the assistant message.
 *
 * The step owns the limit policy of one iteration: assistant text cut at its cap is a failure that also suppresses
 * the execution of the response's tool calls, a step over the per-step call cap is not acted upon at all, a call
 * over the argument cap is dropped while the other calls of the step are still executed, and the turn's iteration
 * bound flags the final step's message while still executing its calls — the turn then ends after them, which is
 * why the outcome can ask the loop to stop.
 *
 * @property llmStreamCollector Collector that owns the provider chunk stream for the iteration.
 * @property conversationTurnPersistence Collaborator that owns the placeholder, the content/reasoning updates
 *            and the terminal state write of the assistant message.
 * @property reasoningCapabilityRecorder Collaborator that records a model's reasoning mode (encrypted vs
 *            plaintext) from observed reasoning items, used to adapt reasoning replay across model switches.
 */
internal class StreamingAssistantStepRunner(
    private val llmStreamCollector: LlmStreamCollector,
    private val conversationTurnPersistence: ConversationTurnPersistence,
    private val reasoningCapabilityRecorder: ReasoningCapabilityRecorder
) {
    /** Logger used for streaming-step diagnostics (finalization, delivery failures, stream errors). */
    private val logger: Logger = LogManager.getLogger(StreamingAssistantStepRunner::class.java)

    /**
     * Executes one streaming assistant iteration.
     *
     * The placeholder row is created as *not completed* and is finalized exactly once: on a normal completion
     * (including one that reached a cap) by the stream-complete callback, and on every abnormal ending (user stop,
     * streaming failure, a provider stream that ends without a terminal chunk, or an unexpected exception) by
     * [finalizeUnfinalized] — always persisting the partial text that had been received together with the
     * terminal state that explains why the generation stopped.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param currentContext Current raw conversation context.
     * @param parentMessageId Parent under which the next assistant message should be persisted.
     * @param isLastToolCallingIteration Whether the orchestrator's tool-calling loop may not run another
     *        iteration after this step. A tool-calling response then cannot be followed up, so its message records
     *        the iteration-limit failure and its calls are still executed (the turn ends once they have run).
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
        val saveResult = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings,
            agentRoleId = request.session.agentRoleId,
            reasoningItems = null,
            // The placeholder is born not-completed and cause-less, so it can never be mistaken for a finished
            // answer and clients render no notice while the generation is still running.
            completion = AssistantMessageCompletionState.InFlight
        )
        emit(
            ConversationTurnEvent.AssistantMessageStarted(
                saveResult.assistantMessage,
                saveResult.updatedParentMessage
            )
        )
        val assistantMessage = saveResult.assistantMessage

        // The accumulator lives here (instead of inside handleLlmStreaming) so every abnormal ending below can
        // persist the exact partial text the stream had received when it stopped.
        val accumulatedContent = StringBuilder()

        // Reasoning items complete asynchronously during streaming; accumulate them so they can be
        // persisted together with the finalized message on completion.
        val accumulatedReasoningItems = mutableListOf<JsonObject>()

        // Enforces the exactly-once rule: once the row carries a terminal state, a later ending of the same
        // step must not re-flag it (an exception raised after a successful finalization, for instance, must
        // not turn a completed answer into a failure). The guard is per invocation on purpose: the runner
        // instance is shared by every turn of every session, so an instance-level flag would skip a later
        // turn's finalization.
        val guard = FinalizationGuard()

        var assistantStepOutcome: AssistantStepOutcome? = null
        llmStreamCollector.handleLlmStreaming(
            context = currentContext,
            model = request.llmConfig.model,
            provider = request.llmConfig.provider,
            settings = request.llmConfig.settings,
            apiKey = request.llmConfig.apiKey,
            tools = request.llmConfig.tools,
            systemMessage = request.llmConfig.systemMessage.takeIf { it.isNotBlank() },
            controlSignal = request.turnControlSignal,
            accumulatedContent = accumulatedContent,
            onContentDelta = { delta ->
                emit(ConversationTurnEvent.AssistantMessageDelta(assistantMessage.id, delta))
            },
            onToolCallChunk = { toolCallChunk ->
                emit(
                    ConversationTurnEvent.ToolCallDelta(
                        messageId = assistantMessage.id,
                        index = toolCallChunk.index,
                        id = toolCallChunk.id,
                        name = toolCallChunk.name ?: "",
                        argumentsDelta = toolCallChunk.argumentsDelta
                    )
                )
            },
            onReasoningChunk = { reasoningDone ->
                // Only the opaque completed item is persisted for replay; plaintext reasoning deltas
                // (ReasoningTextChunk) are render-only and are not accumulated here.
                accumulatedReasoningItems.add(reasoningDone.reasoningItem)
            },
            onStreamComplete = { toolCallRequests, finishReason, contentTruncated, droppedToolCallCount, clippedToolCallArgumentCount ->
                // Sanitize once before the accumulated items enter persistence or the follow-up context.
                val sanitizedReasoningItems = sanitizeReasoningItems(accumulatedReasoningItems)
                // Persist accumulated reasoning (if any) alongside the finalized message content.
                if (sanitizedReasoningItems.isNotEmpty()) {
                    conversationTurnPersistence.updateAssistantMessageReasoning(
                        assistantMessage.id,
                        sanitizedReasoningItems
                    )
                }
                // Record the model's reasoning mode from the accumulated reasoning items (if any) so later
                // replays can adapt what is sent to this model. Detection is a cheap, one-time write.
                reasoningCapabilityRecorder.record(
                    request.llmConfig.model,
                    accumulatedReasoningItems.takeIf { it.isNotEmpty() }
                )
                // The step ends with tool calls that only a follow-up iteration could consume.
                val requestsToolCalls = finishReason == "tool_calls" && toolCallRequests.isNotEmpty()
                // Assistant text cut at the cap is a failure rather than a normal completion, and it is the only
                // limit that also suppresses tool execution: a response cut off mid-generation is untrustworthy
                // input for side-effecting tools.
                val contentFailure = if (contentTruncated) {
                    outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
                } else {
                    null
                }
                // The tool-call limits have their own execution policies: the stream collector already dropped the
                // calls of a step over the per-step cap and the calls whose argument was cut, while the iteration
                // bound keeps the calls of the final step. Only a limit that still leaves calls to run flags the
                // message and asks the loop to end the turn after them.
                val toolCallFailure = ConversationTurnLimits.toolCallLimitFailure(
                    droppedToolCallCount = droppedToolCallCount,
                    clippedToolCallArgumentCount = clippedToolCallArgumentCount,
                    isLastToolCallingIteration = isLastToolCallingIteration,
                    requestsToolCalls = requestsToolCalls
                )
                val updatedAssistantMessage = conversationTurnPersistence.updateAssistantMessageContent(
                    messageId = assistantMessage.id,
                    content = accumulatedContent.toString(),
                    completion = contentFailure ?: toolCallFailure ?: AssistantMessageCompletionState.Completed
                )
                guard.isFinalized = true
                runCatching { emit(ConversationTurnEvent.AssistantMessageFinished(updatedAssistantMessage)) }
                    .onFailure {
                        logger.debug(
                            "Could not deliver AssistantMessageFinished for message ${assistantMessage.id}: ${it.message}"
                        )
                    }

                if (contentFailure == null && toolCallFailure != null) {
                    logToolCallLimitFailure(
                        request = request,
                        assistantMessageId = assistantMessage.id,
                        failure = toolCallFailure,
                        toolCallCount = toolCallRequests.size,
                        droppedToolCallCount = droppedToolCallCount,
                        clippedToolCallArgumentCount = clippedToolCallArgumentCount
                    )
                }

                if (contentFailure != null) {
                    // Tool calls parsed from a truncated response are untrustworthy input for side-effecting tools,
                    // so the turn ends here instead of handing them to processTurn. The requests are discarded
                    // (only their count is logged): no tool-call row is written and nothing is executed, so no
                    // approval prompt or tool badge can appear below a failed message.
                    if (toolCallRequests.isNotEmpty()) {
                        logger.warn(
                            "Discarding ${toolCallRequests.size} tool call request(s) for session " +
                                "${request.session.id}: ${contentFailure.errorMessage} (${contentFailure.errorCode})"
                        )
                    }
                    emit(ConversationTurnEvent.TurnCompleted)
                    assistantStepOutcome = null
                } else if (requestsToolCalls) {
                    // The calls that survived the caps (all of them when only the iteration bound was reached) are
                    // handed to the loop, which persists and executes them. The iteration bound makes this the
                    // turn's last step: its calls still run, and the loop ends the turn once they have. The outcome
                    // is forwarded directly into the next iteration's raw context, so do not expose provider
                    // output-only fields such as `status` or `format` here.
                    assistantStepOutcome = AssistantStepOutcome(
                        assistantMessage = updatedAssistantMessage,
                        toolCallRequests = toolCallRequests,
                        reasoningItems = sanitizedReasoningItems.takeIf { it.isNotEmpty() },
                        endTurnAfterToolCalls = toolCallFailure != null
                    )
                } else {
                    // No call of this step may run — the caps refused all of them, or the response was a plain
                    // answer — so the turn ends here without touching the tool layer.
                    emit(ConversationTurnEvent.TurnCompleted)
                    assistantStepOutcome = null
                }
            },
            onError = { llmError ->
                logger.error(
                    "LLM API streaming error for session ${request.session.id}, provider ${request.llmConfig.provider.name}: $llmError"
                )
                // The transient notification is emitted here; closing the turn is left to the finalizer below,
                // which persists the partial text and the failure state and emits TurnCompleted after it. The
                // error path therefore emits the documented order ExternalServiceError ->
                // AssistantMessageFinished -> TurnCompleted exactly once.
                emit(ConversationTurnEvent.ExternalServiceError(llmError))
            },
            onUnfinalized = { unfinalized ->
                val completion = when (unfinalized) {
                    is UnfinalizedAssistantStream.CancelledByUser ->
                        AssistantMessageCompletionState.InterruptedByUser

                    is UnfinalizedAssistantStream.Failed ->
                        unfinalized.error.toAssistantMessageCompletionState()

                    is UnfinalizedAssistantStream.StreamEndedUnexpectedly ->
                        streamInterruptedCompletionState()

                    is UnfinalizedAssistantStream.UnexpectedFailure ->
                        unexpectedFailureCompletionState()
                }
                finalizeUnfinalized(
                    guard = guard,
                    assistantMessage = assistantMessage,
                    accumulatedContent = accumulatedContent,
                    completion = completion,
                    emit = emit
                )
                // A user stop deliberately ends the turn without a terminal frame, and an unexpected failure is
                // closed by ChatServiceImpl after the exception is rethrown, so neither may emit TurnCompleted
                // here.
                if (unfinalized !is UnfinalizedAssistantStream.CancelledByUser &&
                    unfinalized !is UnfinalizedAssistantStream.UnexpectedFailure
                ) {
                    emit(ConversationTurnEvent.TurnCompleted)
                }
            }
        )

        return assistantStepOutcome
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
                "Tool calls of the step handed to execution: $toolCallCount."
        )
    }

    /**
     * Per-invocation guard of the exactly-once terminal write of one streaming assistant step.
     *
     * The runner is a shared collaborator constructed once (not per turn) and used by every turn of every session, so
     * the guard cannot be instance state: it has to live exactly as long as one [run] invocation.
     * It is therefore created as a local value inside [run] and handed to [finalizeUnfinalized].
     */
    private class FinalizationGuard {
        /**
         * Whether the assistant message of the current step already received its terminal write. Set after the
         * successful write and before the best-effort delivery, so a failed delivery cannot re-open the state.
         */
        var isFinalized: Boolean = false
    }

    /**
     * Persists the accumulated content together with a terminal [completion] state and delivers the terminal
     * event, at most once per invocation of [run].
     *
     * [guard] is the caller's per-invocation state: a message whose row already carries a terminal state is
     * left untouched (and the skip is logged), so the endings that can follow each other — the post-collect
     * classification, the cancellation handler and the unexpected-failure handler — produce exactly one write
     * and at most one [ConversationTurnEvent.AssistantMessageFinished].
     *
     * Only the write is made durable across a cancellation, through `NonCancellable`: it must survive the
     * teardown that ends a stopped turn, and a failed write must never be reported as a recorded state (the
     * guard is therefore set after it, not before). The delivery deliberately runs *outside* that block and in
     * the collector's context, because the turn is a `flow` builder, which rejects an emission from a context
     * whose `Job` chain leaves the collect job — wrapping the emission was what kept the unexpected-failure
     * state from ever reaching a live client. Delivery stays best-effort: on a torn-down socket the client
     * settles the ending itself and the durable state is read on the next session load.
     *
     * @param guard Guard of the invocation that owns this ending; never shared between steps.
     * @param assistantMessage Placeholder row of the step, used as the write target and for diagnostics.
     * @param accumulatedContent Accumulator of the step, persisted as received. It may be empty: a stop before
     *        any content still has to record the terminal state.
     * @param completion Terminal state that explains why the generation stopped.
     * @param emit Sink used to publish the terminal event, after the write.
     */
    private suspend fun finalizeUnfinalized(
        guard: FinalizationGuard,
        assistantMessage: ChatMessage.AssistantMessage,
        accumulatedContent: StringBuilder,
        completion: AssistantMessageCompletionState,
        emit: suspend (ConversationTurnEvent) -> Unit
    ) {
        if (guard.isFinalized) {
            logger.info(
                "Assistant message ${assistantMessage.id} already reached a terminal state; keeping it"
            )
            return
        }
        // Persistence comes first and is deliberately kept outside the delivery below: a failed write must
        // never be reported as a recorded state, and the delivery must never be a precondition for the write.
        // The write is the only thing that has to outlive a cancelled turn (the teardown of a stop): a plain
        // suspension point would abort before touching the row, leaving it without a terminal state forever.
        // Nothing else runs inside this block.
        val updatedAssistantMessage = withContext(NonCancellable) {
            conversationTurnPersistence.updateAssistantMessageContent(
                messageId = assistantMessage.id,
                // May be empty: a stop before any content still has to record the terminal state.
                content = accumulatedContent.toString(),
                completion = completion
            )
        }
        guard.isFinalized = true
        // Delivery is best-effort by design, but it must be *attempted* in the collector's context: on a live
        // socket the client gets the notice without asking again, and on a torn-down one the failure here only
        // means the notice waits for the next session load.
        runCatching { emit(ConversationTurnEvent.AssistantMessageFinished(updatedAssistantMessage)) }
            .onFailure {
                logger.debug(
                    "Could not deliver AssistantMessageFinished for message ${assistantMessage.id}: ${it.message}"
                )
            }
    }
}
