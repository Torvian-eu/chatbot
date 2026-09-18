package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.CancellationException

/**
 * Collects one streaming LLM response and folds its chunk sequence into the caller's turn state.
 *
 * The collector owns the provider-stream half of a streaming assistant step: it applies the assistant-text,
 * per-step tool-call and tool-argument character caps while appending, reports every reached cap back through the
 * [handleLlmStreaming] callbacks (nothing is cut silently), reconstructs tool calls from their streamed deltas,
 * reports streaming errors, and classifies every ending that carries no terminal chunk as an
 * [UnfinalizedAssistantStream] so the caller can persist the matching terminal state exactly once. No content is
 * stored here — the caller owns the [StringBuilder] it passes in and receives the accepted deltas through the
 * [handleLlmStreaming] callbacks, which is what keeps the recorded text and the recorded ending consistent.
 *
 * @property llmApiClient Client that exposes the raw provider chunk stream used for generation.
 */
internal class LlmStreamCollector(
    private val llmApiClient: LLMApiClient
) {
    /** Logger used for provider-stream diagnostics (error chunks, usage stats, dropped reasoning deltas). */
    private val logger: Logger = LogManager.getLogger(LlmStreamCollector::class.java)

    /**
     * Collects a streaming LLM response, accumulating assistant content and tool-call deltas.
     *
     * Every ending that is *not* a provider-signalled completion is reported to [onUnfinalized] so the caller
     * can persist the terminal state of the message exactly once. The collector deliberately does not switch the
     * coroutine context around those reports: durability is guaranteed by the handler itself (the caller wraps its
     * single write in `NonCancellable`), while an extra wrapper here would make the terminal event undeliverable —
     * the enclosing `flow` builder rejects an emission from a context whose `Job` chain leaves the collect job.
     * The cancellation and unexpected-failure endings are therefore reported from the exception handlers in the
     * collector's own context, and the drain-completed stop and a stream that simply ends without a terminal chunk
     * are reported after the collect returns.
     *
     * @param context Raw conversation context sent to the LLM.
     * @param model Model used for generation.
     * @param provider Provider used for generation.
     * @param settings Chat settings applied to the request.
     * @param apiKey Optional provider API key.
     * @param tools Enabled tools available for the request.
     * @param systemMessage Composed system prompt (single source of truth), or null when absent.
     * @param controlSignal Cooperative control signal observed before every chunk.
     * @param accumulatedContent Accumulator owned by the caller and filled with the accepted streamed text
     *        (bounded by [ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS]); the caller persists it
     *        on every ending.
     * @param onContentDelta Callback for assistant text deltas.
     * @param onToolCallChunk Callback for streamed tool-call chunks.
     * @param onReasoningChunk Callback for the completed, opaque reasoning item emitted by the provider.
     * @param onStreamComplete Callback invoked after the provider signals stream completion, receiving the
     *        parsed tool-call requests, the finish reason, whether the character cap was reached, and how many
     *        tool calls the per-step and per-argument caps cut.
     * @param onError Callback for streaming errors.
     * @param onUnfinalized Callback reporting an ending without a terminal chunk, carrying the text received so
     *        far and the reason the stream stopped.
     */
    internal suspend fun handleLlmStreaming(
        context: List<RawChatMessage>,
        model: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?,
        controlSignal: TurnControlSignal,
        accumulatedContent: StringBuilder,
        onContentDelta: suspend (deltaContent: String) -> Unit,
        onToolCallChunk: suspend (toolCallChunk: LLMStreamChunk.ToolCallChunk) -> Unit,
        onReasoningChunk: suspend (reasoningDone: LLMStreamChunk.ReasoningDone) -> Unit,
        onStreamComplete: suspend (
            toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
            finishReason: String?,
            contentTruncated: Boolean,
            droppedToolCallCount: Int,
            clippedToolCallArgumentCount: Int
        ) -> Unit,
        onError: suspend (error: LLMCompletionError) -> Unit,
        onUnfinalized: suspend (unfinalized: UnfinalizedAssistantStream) -> Unit
    ) {
        val state = StreamCollectionState()

        try {
            llmApiClient.completeChatStreaming(context, model, provider, settings, apiKey, tools, systemMessage)
                .collect { llmStreamChunkEither ->
                    if (controlSignal.isCancelled) return@collect
                    llmStreamChunkEither.fold(
                        ifLeft = { llmError ->
                            logger.error("LLM API streaming error, provider ${provider.name}: $llmError")
                            state.streamingError = llmError
                            onError(llmError)
                        },
                        ifRight = { chunk ->
                            handleStreamChunk(
                                chunk = chunk,
                                state = state,
                                accumulatedContent = accumulatedContent,
                                onContentDelta = onContentDelta,
                                onToolCallChunk = onToolCallChunk,
                                onReasoningChunk = onReasoningChunk,
                                onStreamComplete = onStreamComplete,
                                onError = onError
                            )
                        }
                    )
                }
        } catch (cancellationException: CancellationException) {
            // The socket was torn down while the provider stream was still open (hard teardown of a stop). The
            // partial text and the interruption cause must survive the cancellation: the handler runs here in the
            // collector's own context and persists them under NonCancellable on its own. Delivering the terminal
            // event is best-effort and usually impossible here (the socket is gone), so the client settles this
            // ending locally and the persisted state is read on the next session load.
            logger.info("LLM streaming cancelled, accumulated content length: ${accumulatedContent.length}")
            runCatching {
                onUnfinalized(UnfinalizedAssistantStream.CancelledByUser(accumulatedContent.toString()))
            }.onFailure { handlerError ->
                logger.error(
                    "Failed to persist interrupted assistant message: ${handlerError.message}",
                    handlerError
                )
            }
            throw cancellationException
        } catch (unexpected: Throwable) {
            // A non-cancellation failure of the provider flow or of one of the callbacks: the message may have
            // been left unfinished, so record it as an unexpected failure before the exception is rethrown to
            // ChatServiceImpl (which still maps it to ProcessNewMessageError.UnexpectedError). The socket is
            // generally still alive here, so the handler persists *and* delivers the terminal state from this
            // context; wrapping this call in `NonCancellable` would suppress that delivery.
            logger.error("Unexpected failure during LLM streaming for provider ${provider.name}: $unexpected", unexpected)
            runCatching {
                onUnfinalized(UnfinalizedAssistantStream.UnexpectedFailure(accumulatedContent.toString()))
            }.onFailure { handlerError ->
                logger.error(
                    "Failed to persist unfinished assistant message: ${handlerError.message}",
                    handlerError
                )
            }
            throw unexpected
        }

        // The collect returned without a terminal chunk. Two endings reach this point: the user stopped the
        // turn and the provider stream happened to end while the client was still connected (drain-completed
        // stop), or the stream ended early without any signal at all. In both cases the row would stay
        // not-completed without a cause forever, so the terminal state is written here.
        if (!state.streamCompleted) {
            val partialContent = accumulatedContent.toString()
            val lastStreamingError = state.streamingError
            onUnfinalized(
                when {
                    controlSignal.isCancelled ->
                        UnfinalizedAssistantStream.CancelledByUser(partialContent)

                    lastStreamingError != null ->
                        UnfinalizedAssistantStream.Failed(partialContent, lastStreamingError)

                    else ->
                        UnfinalizedAssistantStream.StreamEndedUnexpectedly(partialContent)
                }
            )
        }
    }

    /**
     * Handles one provider chunk by kind, mutating the invocation's collection state and accumulator.
     *
     * The chunk switch lives here instead of inside the stream's `collect` lambda so the per-chunk rules stay
     * readable without pushing every statement four blocks deep. The stream wiring itself — the call, the
     * cancellation check, the two exception paths and the final classification — stays in [handleLlmStreaming].
     * Nothing about mutation and callback order changes: inside an arm the accumulator is updated before the
     * delta is forwarded, `ToolCallDone` only overrides the authoritative payload, and inside `Done` the
     * completion flag and the finish reason are set before [onStreamComplete] runs.
     *
     * @param chunk Chunk emitted by the provider stream.
     * @param state Collection state of the current invocation, updated in place.
     * @param accumulatedContent Accumulator owned by the caller, bounded here and appended in place.
     * @param onContentDelta Callback for the accepted assistant text deltas.
     * @param onToolCallChunk Callback for the tool-call deltas that survive the argument cap.
     * @param onReasoningChunk Callback for the completed, opaque reasoning item emitted by the provider.
     * @param onStreamComplete Callback invoked after the provider signals stream completion, receiving the
     *        parsed tool-call requests, the finish reason, whether the character cap was reached, and how many
     *        tool calls the per-step and per-argument caps cut.
     * @param onError Callback for streaming errors that arrive as a chunk.
     */
    private suspend fun handleStreamChunk(
        chunk: LLMStreamChunk,
        state: StreamCollectionState,
        accumulatedContent: StringBuilder,
        onContentDelta: suspend (deltaContent: String) -> Unit,
        onToolCallChunk: suspend (toolCallChunk: LLMStreamChunk.ToolCallChunk) -> Unit,
        onReasoningChunk: suspend (reasoningDone: LLMStreamChunk.ReasoningDone) -> Unit,
        onStreamComplete: suspend (
            toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
            finishReason: String?,
            contentTruncated: Boolean,
            droppedToolCallCount: Int,
            clippedToolCallArgumentCount: Int
        ) -> Unit,
        onError: suspend (error: LLMCompletionError) -> Unit
    ) {
        when (chunk) {
            is LLMStreamChunk.ContentChunk -> {
                val remainingChars =
                    ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS - accumulatedContent.length
                if (remainingChars <= 0) {
                    // The cap is already exhausted: the whole delta is dropped, including an empty one.
                    state.contentTruncated = true
                    return
                }
                val allowedDelta = chunk.deltaContent.take(remainingChars)
                // Record the cut before the accepted prefix is appended, so the flag never lags the content.
                if (allowedDelta.length < chunk.deltaContent.length) {
                    state.contentTruncated = true
                }
                accumulatedContent.append(allowedDelta)
                if (allowedDelta.isNotEmpty()) {
                    onContentDelta(allowedDelta)
                }
                if (chunk.finishReason != null) {
                    state.finishReason = chunk.finishReason
                }
            }

            is LLMStreamChunk.ToolCallChunk -> {
                val index = chunk.index ?: 0
                if (index >= ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP) {
                    // The per-step cap drops the whole call: nothing is accumulated for it and nothing is
                    // forwarded to the live UI. The dropped index is recorded (distinct indices are distinct
                    // calls) so the caller can flag the message and end the turn instead of continuing with a
                    // silently reduced batch.
                    state.droppedToolCallIndices.add(index)
                    return
                }
                val accumulator = state.toolCallsByIndex.getOrPut(index) {
                    MutableToolCallAccumulator(
                        id = chunk.id,
                        name = chunk.name ?: "",
                        arguments = StringBuilder()
                    )
                }

                if (chunk.id != null && accumulator.id == null) {
                    accumulator.id = chunk.id
                }
                if (!chunk.name.isNullOrEmpty() && accumulator.name.isEmpty()) {
                    accumulator.name = chunk.name
                }
                val argumentsDelta = chunk.argumentsDelta
                if (argumentsDelta == null) {
                    onToolCallChunk(chunk)
                    return
                }
                // A full argument buffer drops the delta silently from the live UI, but the call is remembered
                // as clipped: its persisted payload is no longer valid tool input, which the caller reports.
                val remaining =
                    ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS - accumulator.arguments.length
                if (remaining <= 0) {
                    accumulator.argumentsClipped = true
                    return
                }
                val allowedDelta = argumentsDelta.take(remaining)
                // Record the cut before the accepted prefix is appended, so the flag never lags the arguments.
                if (allowedDelta.length < argumentsDelta.length) {
                    accumulator.argumentsClipped = true
                }
                accumulator.arguments.append(allowedDelta)
                if (allowedDelta.isNotEmpty()) {
                    onToolCallChunk(chunk.copy(argumentsDelta = allowedDelta))
                }
            }

            is LLMStreamChunk.ToolCallDone -> {
                // The provider's authoritative final function call. It may carry a corrected arguments string
                // (providers can fix up the raw delta stream), so override the delta-accumulated accumulator
                // for this output_index. This chunk is optional (only the Responses dialect emits it); when it
                // is absent, the delta-accumulated values are used unchanged.
                val index = chunk.index ?: 0
                if (index >= ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP) {
                    // Same per-step rule as above: the call is dropped as a whole and only recorded so the
                    // caller can report the cap.
                    state.droppedToolCallIndices.add(index)
                    return
                }
                val authoritativeArguments = chunk.arguments
                state.toolCallsByIndex[index] = MutableToolCallAccumulator(
                    id = chunk.id,
                    name = chunk.name,
                    arguments = StringBuilder(
                        authoritativeArguments?.take(
                            ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
                        ) ?: ""
                    ),
                    // The authoritative payload replaces whatever the deltas accumulated, including a previous
                    // clip, so only a cut applied here leaves the call marked as clipped.
                    argumentsClipped = authoritativeArguments != null &&
                        authoritativeArguments.length > ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
                )
                // The live-UI deltas were already streamed via ToolCallChunk; this chunk only corrects the
                // authoritative payload used for execution/persistence.
            }

            is LLMStreamChunk.UsageChunk -> {
                logger.debug(
                    "Usage stats: prompt=${chunk.promptTokens}, completion=${chunk.completionTokens}, total=${chunk.totalTokens}, reasoning=${chunk.reasoningTokens}"
                )
            }

            is LLMStreamChunk.ReasoningDone -> {
                // Reasoning items are opaque and forwarded as-is so the caller can accumulate and persist
                // them for replay; they are never rendered.
                onReasoningChunk(chunk)
            }

            is LLMStreamChunk.ReasoningTextChunk -> {
                // Plaintext reasoning deltas are intended for live UI rendering and are not part of the
                // persisted transcript. When no live-rendering consumer is wired, they carry no side effect
                // here. Never persist or replay them.
                logger.trace(
                    "Reasoning text delta discarded (no UI consumer): index=${chunk.outputIndex}, " +
                            "contentIndex=${chunk.contentIndex}, delta=${chunk.delta.take(200)}"
                )
            }

            LLMStreamChunk.Done -> {
                // The provider signalled completion: from here on the caller finalizes the message itself and
                // no abnormal-ending finalizer may run.
                state.streamCompleted = true
                val toolCallRequests = state.survivingToolCalls().map { accumulator ->
                    LLMCompletionResult.CompletionChoice.ToolCallRequest(
                        name = accumulator.name,
                        arguments = accumulator.arguments.toString().takeIf { it.isNotEmpty() },
                        toolCallId = accumulator.id
                    )
                }

                if (state.finishReason == null && toolCallRequests.isNotEmpty()) {
                    state.finishReason = "tool_calls"
                }

                // The caller owns the accumulated content and reports truncation through the completion state
                // instead of an in-content notice; the tool-call caps of this step are reported the same way
                // (counts of 0 mean the cap was not reached), while the calls they refuse are already removed
                // from [toolCallRequests] here (all of them under the per-step cap, only the clipped ones under
                // the argument cap).
                onStreamComplete(
                    toolCallRequests,
                    state.finishReason,
                    state.contentTruncated,
                    state.droppedToolCallIndices.size,
                    state.toolCallsByIndex.values.count { it.argumentsClipped }
                )
            }

            is LLMStreamChunk.Error -> {
                logger.error("LLM API returned streaming error chunk: ${chunk.llmError}")
                state.streamingError = chunk.llmError
                onError(chunk.llmError)
            }
        }
    }
}

/**
 * Mutable collection state of one [LlmStreamCollector.handleLlmStreaming] call.
 *
 * The state is created per invocation and is reachable only from that invocation's call tree, so moving the
 * chunk switch into [LlmStreamCollector.handleStreamChunk] does not turn any of it into collector-level
 * (cross-turn) state. It carries exactly the values the chunk handling has to remember while the provider
 * stream is drained; the accumulated text itself stays in the caller-owned [StringBuilder].
 *
 * @property contentTruncated Whether the assistant-text cap was reached, i.e. whether streamed text had to be
 *            dropped.
 * @property finishReason Last finish reason reported by the provider, or the derived `tool_calls` value once
 *            the step turned out to carry tool calls.
 * @property streamCompleted Whether the provider delivered its terminal chunk; anything else is an abnormal
 *            ending that the caller has to finalize itself, because nothing else will ever touch the row.
 * @property streamingError Last error reported by the stream, or `null` when none was reported. An error chunk
 *            may be followed by the end of the flow, and the ending then has to be classified as a failure
 *            rather than as a bare stream interruption.
 * @property toolCallsByIndex Tool-call accumulators of the current step, keyed by its sequential tool-call index;
 *            the insertion order is the order the calls were first seen in, which is the order the requests are
 *            materialized in.
 * @property droppedToolCallIndices Output indices of the tool calls refused by the per-step cap. The indices are
 *            collected in a set because one call can arrive in many chunks but counts once; nothing is
 *            accumulated for them, so the caller learns about the cap from this field alone.
 */
private class StreamCollectionState {
    var contentTruncated: Boolean = false
    var finishReason: String? = null
    var streamCompleted: Boolean = false
    var streamingError: LLMCompletionError? = null
    val toolCallsByIndex: MutableMap<Int, MutableToolCallAccumulator> = mutableMapOf()
    val droppedToolCallIndices: MutableSet<Int> = mutableSetOf()

    /**
     * Selects the tool calls of this step that may be persisted and executed.
     *
     * The tool-call caps decide this, and they decide it here so no caller can hand a refused call to the tool
     * layer: a step that asked for more calls than the per-step cap allows is not acted upon at all (a batch that
     * size means something went wrong with the response), while a call whose argument had to be cut at the argument
     * cap refuses only itself — the truncated payload is no longer the input the model sent — whereas the other
     * calls of the step are complete and independent of it, so they are still returned in the order they were first
     * seen. Dropping a call never shifts the position of the remaining ones, and a call is clipped only while no
     * authoritative payload corrected it (see [MutableToolCallAccumulator.argumentsClipped]).
     *
     * @return Tool calls of the step that are within the caps, in the order they were first seen; empty when nothing
     *         of the step may run (the per-step cap refused the batch, or every call of it was clipped).
     */
    fun survivingToolCalls(): List<MutableToolCallAccumulator> {
        if (droppedToolCallIndices.isNotEmpty()) return emptyList()
        return toolCallsByIndex.values.filterNot { it.argumentsClipped }
    }
}

/**
 * Mutable accumulator used while reconstructing tool calls from streaming deltas.
 *
 * @property id Provider tool-call identifier as soon as it becomes available.
 * @property name Tool name once emitted by the provider.
 * @property arguments Incrementally accumulated arguments payload, bounded by
 *            [ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS].
 * @property argumentsClipped Whether the argument payload of this call had to be cut at the character cap, i.e.
 *            whether the payload persisted for it is a prefix that no longer parses as the input the model
 *            sent. Reset when an authoritative `ToolCallDone` replaces the accumulated payload.
 */
private data class MutableToolCallAccumulator(
    var id: String?,
    var name: String,
    val arguments: StringBuilder,
    var argumentsClipped: Boolean = false
)
