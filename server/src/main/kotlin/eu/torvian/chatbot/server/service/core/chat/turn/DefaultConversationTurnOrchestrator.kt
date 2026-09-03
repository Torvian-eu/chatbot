package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.CancellationException

/**
 * Default implementation that owns the shared assistant/tool loop for a single conversation turn.
 *
 * @property llmApiClient Client used for streaming and non-streaming LLM calls.
 * @property toolCallOrchestrator Collaborator that handles approval and tool execution.
 * @property toolResultContentBuilder Serializer for completed tool results appended back into context.
 * @property chatContextBuilder Builder that reconstructs the threaded LLM context.
 * @property conversationTurnPersistence Collaborator that owns message and tool-call persistence workflow.
 * @property reasoningCapabilityRecorder Collaborator that records a model's reasoning mode (encrypted vs
 *            plaintext) from observed reasoning items, used to adapt reasoning replay across model switches.
 * @property conversationCompactionService Policy that runs before every primary LLM call and may replace
 *            the oversized primary context with one labeled synthetic summary.
 */
class DefaultConversationTurnOrchestrator(
    private val llmApiClient: LLMApiClient,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val toolResultContentBuilder: ToolResultContentBuilder,
    private val chatContextBuilder: ChatContextBuilder,
    private val conversationTurnPersistence: ConversationTurnPersistence,
    private val reasoningCapabilityRecorder: ReasoningCapabilityRecorder,
    private val conversationCompactionService: ConversationCompactionService,
) : ConversationTurnOrchestrator {

    companion object {
        /** Logger used for turn-runtime diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultConversationTurnOrchestrator::class.java)

        /** Upper bound that prevents an unbounded assistant/tool loop. */
        private const val MAX_TOOL_CALLING_ITERATIONS: Int = 200

        /** Maximum character length of persisted assistant text before the truncation notice. */
        const val MAX_ASSISTANT_MESSAGE_CHARS: Int = 64_000

        /** Maximum characters allowed for a single tool call argument payload. */
        const val MAX_TOOL_CALL_ARGUMENT_CHARS: Int = 100_000

        /** Maximum number of tool calls allowed in a single assistant step. */
        const val MAX_TOOL_CALLS_PER_STEP: Int = 20

        /** Visible marker explaining why assistant output was shortened. */
        private const val ASSISTANT_TRUNCATION_NOTICE =
            "\n\n[Output truncated: the response exceeded 64000 characters.]"
    }

    /**
     * Processes a non-streaming turn by delegating the assistant step to the full-response LLM path.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    override fun processNonStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent> = flow {
        processTurn(
            request = request,
            processAssistantStep = ::processNonStreamingAssistantStep,
            emit = { event -> emit(event) }
        )
    }

    /**
     * Processes a streaming turn by delegating the assistant step to the chunked LLM path.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    override fun processStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent> = flow {
        processTurn(
            request = request,
            processAssistantStep = ::processStreamingAssistantStep,
            emit = { event -> emit(event) }
        )
    }

    /**
     * Runs the shared turn lifecycle, independent of assistant delivery mode.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param processAssistantStep Mode-specific assistant generation function.
     * @param emit Sink used to publish lifecycle events.
     */
    private suspend fun processTurn(
        request: ConversationTurnRequest,
        processAssistantStep: suspend (
            request: ConversationTurnRequest,
            currentContext: List<RawChatMessage>,
            parentMessageId: Long,
            emit: suspend (ConversationTurnEvent) -> Unit
        ) -> AssistantStepOutcome?,
        emit: suspend (ConversationTurnEvent) -> Unit
    ) {
        val preparedTurn = prepareTurn(request, emit)
        var lastMessageId = preparedTurn.lastMessageId
        var iterationCount = 0

        // The full identity-bearing source context is handed to the compaction state once at turn
        // start: it initializes the rolling window (eligible prior summary + delta, or the full
        // thread) and the identity ledger, after which the full uncompressed content is released —
        // the window (one optional summary + additional uncompressed messages) and the ledger are the
        // loop's only conversation state from here on. A structurally invalid preference fails here
        // (InvalidConfiguration left) and aborts the turn before any counting or primary call.
        val compactionState = conversationCompactionService.beginTurn(
            userId = request.userId,
            sessionId = request.session.id,
            initialUnits = preparedTurn.conversationContext.units
        ).getOrElse { error ->
            logger.error(
                "Conversation compaction setup failed for session ${request.session.id}: $error"
            )
            emit(ConversationTurnEvent.CompactionFailed(error))
            emit(ConversationTurnEvent.TurnCompleted)
            return
        }

        while (iterationCount < MAX_TOOL_CALLING_ITERATIONS && !request.turnControlSignal.isCancelled) {
            if (request.turnControlSignal.isPaused) {
                logger.info(
                    "Turn paused for session ${request.session.id}; halting before next LLM iteration"
                )
                emit(ConversationTurnEvent.TurnCompleted)
                break
            }

            // Exact compaction integration point: runs once for the initial primary call and once for
            // every post-tool primary call. A failure terminates the turn before any primary request.
            val preflight = conversationCompactionService.preparePrimaryContext(
                state = compactionState,
                primaryConfig = request.llmConfig,
                expectedLeafMessageId = lastMessageId
            ).getOrElse { error ->
                logger.error(
                    "Conversation compaction preflight failed for session ${request.session.id}: $error"
                )
                emit(ConversationTurnEvent.CompactionFailed(error))
                emit(ConversationTurnEvent.TurnCompleted)
                break
            }

            iterationCount++
            // FR-12 emission rule: the compaction notification is emitted only when this preflight
            // persisted a chunk AND the turn proceeds to the primary call. Emitting here (immediately
            // before processAssistantStep) keeps the event strictly tied to usage — nothing is emitted
            // on disabled/fit/hybrid-reuse paths (no persisted chunk).
            preflight.persistedChunkIfAny?.let { persistedChunk ->
                emit(ConversationTurnEvent.CompactionCompleted(persistedChunk))
            }
            // preflight.primaryMessages is the rolling window verified to fit the threshold: the
            // original flattened thread (first preflight, raw fits), the hybrid [summary] + additional
            // uncompressed messages (the steady state), or the summary message alone right after a
            // compaction (the user's compaction instruction is expected to make that summary
            // self-contained enough to continue from).
            val assistantStep = processAssistantStep(
                request,
                preflight.primaryMessages,
                lastMessageId,
                emit
            ) ?: break
            lastMessageId = assistantStep.assistantMessage.id

            val pendingToolCalls = conversationTurnPersistence.persistPendingToolCalls(
                messageId = assistantStep.assistantMessage.id,
                toolCallRequests = assistantStep.toolCallRequests,
                enabledTools = request.llmConfig.tools
            )
            emit(ConversationTurnEvent.ToolCallsReceived(pendingToolCalls))

            val completedToolCalls = executeToolCalls(request, pendingToolCalls, emit)
            // Grow the rolling window with the newly completed assistant/tool unit so the next
            // preflight covers it (the follow-up iteration sees the appended unit; the service's own
            // window/ledger state is updated by the preflight itself, so there is no record hook).
            compactionState.appendUnit(
                source = SourceMessageSnapshot(
                    id = assistantStep.assistantMessage.id,
                    updatedAt = assistantStep.assistantMessage.updatedAt
                ),
                rawMessages = assistantAndToolResultMessages(
                    assistantStep = assistantStep,
                    completedToolCalls = completedToolCalls,
                    reasoningModelId = request.llmConfig.model.id
                )
            )
        }
    }

    /**
     * Performs shared setup before the assistant/tool loop starts.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param emit Sink used to publish lifecycle events.
     * @return Initial loop state containing the parent message anchor and built context.
     */
    private suspend fun prepareTurn(
        request: ConversationTurnRequest,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): PreparedTurnState {
        var lastMessageId: Long
        val updatedSessionMessages = if (request.content != null) {
            val userMessage = conversationTurnPersistence.saveUserMessage(
                sessionId = request.session.id,
                content = request.content,
                parentMessageId = request.parentMessageId,
                fileReferences = request.fileReferences
            ).let { persistedUserMessage ->
                emit(
                    ConversationTurnEvent.UserMessageSaved(
                        persistedUserMessage.userMessage,
                        persistedUserMessage.updatedParentMessage
                    )
                )
                persistedUserMessage.userMessage
            }
            lastMessageId = userMessage.id
            request.session.messages + userMessage
        } else {
            val parentMessageId = request.parentMessageId
                ?: throw IllegalStateException("parentMessageId is null in Branch & Continue mode")
            lastMessageId = parentMessageId
            request.session.messages
        }

        val sessionToolCalls = conversationTurnPersistence.loadSessionToolCalls(request.session.id)
        val conversationContext = chatContextBuilder.buildContext(
            startingMessageId = lastMessageId,
            sessionMessages = updatedSessionMessages,
            toolCalls = sessionToolCalls
        )

        return PreparedTurnState(lastMessageId = lastMessageId, conversationContext = conversationContext)
    }

    /**
     * Executes one non-streaming assistant iteration.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param currentContext Current raw conversation context.
     * @param parentMessageId Parent under which the next assistant message should be persisted.
     * @param emit Sink used to publish lifecycle events.
     * @return Assistant-step outcome when tool execution should continue, or `null` when the turn is finished.
     */
    private suspend fun processNonStreamingAssistantStep(
        request: ConversationTurnRequest,
        currentContext: List<RawChatMessage>,
        parentMessageId: Long,
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
            emit(ConversationTurnEvent.ExternalServiceError(error))
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        logger.info("LLM API call successful for session ${request.session.id}")

        // Record the model's reasoning mode (encrypted vs plaintext) from the observed reasoning items so
        // later replays can adapt what is sent to this model. Detection is a cheap, one-time write.
        reasoningCapabilityRecorder.record(request.llmConfig.model, llmCompletionResult.reasoningItems)

        val choice = llmCompletionResult.choices.firstOrNull() ?: run {
            logger.error("LLM API returned successful response with no choices for session ${request.session.id}")
            emit(
                ConversationTurnEvent.ExternalServiceError(
                    LLMCompletionError.InvalidResponseError(
                        "LLM API returned success but no completion choices."
                    )
                )
            )
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        val originalContent = choice.content ?: ""
        val content = if (originalContent.length > MAX_ASSISTANT_MESSAGE_CHARS) {
            originalContent.take(MAX_ASSISTANT_MESSAGE_CHARS) + ASSISTANT_TRUNCATION_NOTICE
        } else {
            originalContent
        }
        // Sanitize once before the items enter persistence or the follow-up tool-loop context.
        val sanitizedReasoningItems = llmCompletionResult.reasoningItems?.let(::sanitizeReasoningItems)
        val persistedAssistantMessage = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = content,
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings,
            agentRoleId = request.session.agentRoleId,
            reasoningItems = sanitizedReasoningItems
        )
        emit(
            ConversationTurnEvent.AssistantMessageSaved(
                persistedAssistantMessage.assistantMessage,
                persistedAssistantMessage.updatedParentMessage
            )
        )
        val assistantMessage = persistedAssistantMessage.assistantMessage

        val boundedToolCalls = choice.toolCalls
            ?.take(MAX_TOOL_CALLS_PER_STEP)
            ?.map { toolCall ->
                toolCall.copy(arguments = toolCall.arguments?.take(MAX_TOOL_CALL_ARGUMENT_CHARS))
            }

        if (choice.finishReason != "tool_calls" || boundedToolCalls.isNullOrEmpty()) {
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        return AssistantStepOutcome(
            assistantMessage = assistantMessage,
            assistantContent = content,
            toolCallRequests = boundedToolCalls,
            reasoningItems = sanitizedReasoningItems
        )
    }

    /**
     * Executes one streaming assistant iteration.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param currentContext Current raw conversation context.
     * @param parentMessageId Parent under which the next assistant message should be persisted.
     * @param emit Sink used to publish lifecycle events.
     * @return Assistant-step outcome when tool execution should continue, or `null` when the turn is finished.
     */
    private suspend fun processStreamingAssistantStep(
        request: ConversationTurnRequest,
        currentContext: List<RawChatMessage>,
        parentMessageId: Long,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): AssistantStepOutcome? {
        val saveResult = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings,
            agentRoleId = request.session.agentRoleId,
            reasoningItems = null
        )
        emit(
            ConversationTurnEvent.AssistantMessageStarted(
                saveResult.assistantMessage,
                saveResult.updatedParentMessage
            )
        )
        val assistantMessage = saveResult.assistantMessage

        // Reasoning items complete asynchronously during streaming; accumulate them so they can be
        // persisted together with the finalized message on completion.
        val accumulatedReasoningItems = mutableListOf<JsonObject>()

        var assistantStepOutcome: AssistantStepOutcome? = null
        handleLlmStreaming(
            context = currentContext,
            model = request.llmConfig.model,
            provider = request.llmConfig.provider,
            settings = request.llmConfig.settings,
            apiKey = request.llmConfig.apiKey,
            tools = request.llmConfig.tools,
            systemMessage = request.llmConfig.systemMessage.takeIf { it.isNotBlank() },
            controlSignal = request.turnControlSignal,
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
            onStreamComplete = { finalContent, toolCallRequests, finishReason ->
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
                val updatedAssistantMessage = conversationTurnPersistence.updateAssistantMessageContent(
                    messageId = assistantMessage.id,
                    content = finalContent
                )
                emit(ConversationTurnEvent.AssistantMessageFinished(updatedAssistantMessage))

                if (finishReason != "tool_calls" || toolCallRequests.isEmpty()) {
                    emit(ConversationTurnEvent.TurnCompleted)
                    assistantStepOutcome = null
                } else {
                    // The outcome is forwarded directly into the next iteration's raw context, so do not
                    // expose provider output-only fields such as `status` or `format` here.
                    assistantStepOutcome = AssistantStepOutcome(
                        assistantMessage = updatedAssistantMessage,
                        assistantContent = updatedAssistantMessage.content,
                        toolCallRequests = toolCallRequests,
                        reasoningItems = sanitizedReasoningItems.takeIf { it.isNotEmpty() }
                    )
                }
            },
            onError = { llmError ->
                logger.error(
                    "LLM API streaming error for session ${request.session.id}, provider ${request.llmConfig.provider.name}: $llmError"
                )
                emit(ConversationTurnEvent.ExternalServiceError(llmError))
                emit(ConversationTurnEvent.TurnCompleted)
            },
            onCancellation = { partialContent ->
                if (partialContent.isNotEmpty()) {
                    logger.info(
                        "Saving partial content for cancelled message ${assistantMessage.id}: ${partialContent.length} characters"
                    )
                    conversationTurnPersistence.updateAssistantMessageContent(
                        messageId = assistantMessage.id,
                        content = partialContent
                    )
                } else {
                    logger.info("No partial content to save for cancelled message ${assistantMessage.id}")
                }
            }
        )

        return assistantStepOutcome
    }

    /**
     * Executes persisted tool calls and mirrors the tool orchestrator's lifecycle back into turn events.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param pendingToolCalls Persisted tool calls awaiting execution.
     * @param emit Sink used to publish lifecycle events.
     * @return Completed tool calls that should be appended back into the LLM context.
     */
    private suspend fun executeToolCalls(
        request: ConversationTurnRequest,
        pendingToolCalls: List<ToolCall>,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): List<ToolCall> {
        val completedToolCalls = mutableListOf<ToolCall>()
        // Prepared production turns always carry a role: turn preparation rejects role-less sessions,
        // so a missing role here is an integration error. A zero fallback would silently fail every
        // operator allow-list lookup in the spawn builder, so fail loudly instead.
        val requestingAgentRoleId = request.session.agentRoleId
            ?: throw IllegalStateException(
                "Cannot execute tool calls for session ${request.session.id}: no agent role selected"
            )
        val executionEvents = toolCallOrchestrator.executeAndUpdateToolCalls(
            request.userId,
            requestingAgentRoleId,
            pendingToolCalls,
            request.llmConfig.tools,
            request.toolApprovalFlow,
            request.operatorToolResultFlow,
            request.turnControlSignal
        )
        executionEvents.collect { event ->
            when (event) {
                is ToolCallExecutionEvent.ToolCallExecuting -> {
                    emit(ConversationTurnEvent.ToolCallExecuting(event.toolCall))
                }

                is ToolCallExecutionEvent.ToolCallCompleted -> {
                    completedToolCalls.add(event.toolCall)
                    emit(ConversationTurnEvent.ToolExecutionCompleted(event.toolCall))
                }

                is ToolCallExecutionEvent.ToolCallApprovalRequested -> {
                    emit(ConversationTurnEvent.ToolCallApprovalRequested(event.toolCall))
                }

                is ToolCallExecutionEvent.OperatorToolExecutionRequested -> {
                    emit(
                        ConversationTurnEvent.OperatorToolExecutionRequested(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            payload = event.payloadJson
                        )
                    )
                }
            }
        }
        return completedToolCalls
    }

    /**
     * Derives the provider-facing raw messages for one completed assistant source unit.
     *
     * @param assistantStep Completed assistant step whose content and reasoning enter the unit.
     * @param completedToolCalls Completed tool calls whose calls and results should be appended.
     * @param reasoningModelId ID of the model that produced the step's reasoning items (the current
     *            turn's model), used to gate encrypted reasoning replay on the follow-up LLM request.
     * @return The ordered raw messages (assistant message followed by its tool results) appended as one
     *         source unit, so compaction can never split the call from its results.
     */
    private fun assistantAndToolResultMessages(
        assistantStep: AssistantStepOutcome,
        completedToolCalls: List<ToolCall>,
        reasoningModelId: Long?
    ): List<RawChatMessage> {
        // Derive both provider messages from the same ordered collection so a result can never
        // be emitted without its matching assistant tool call. Every recorded call is replayed,
        // so a tool-calling assistant step always carries its full set of calls in context.
        val assistantContextMessage = RawChatMessage.Assistant(
            content = assistantStep.assistantContent,
            toolCalls = completedToolCalls.map { toolCall ->
                RawChatMessage.Assistant.ToolCall(
                    id = toolCall.toolCallId,
                    name = toolCall.toolName,
                    arguments = toolCall.input
                )
            },
            reasoningItems = assistantStep.reasoningItems,
            reasoningModelId = reasoningModelId
        )
        // A provider transcript must not contain a result without its replayed assistant call.
        val toolResultMessages = completedToolCalls.map { toolCall ->
            RawChatMessage.Tool(
                content = toolResultContentBuilder.build(toolCall),
                toolCallId = toolCall.toolCallId ?: "",
                name = toolCall.toolName
            )
        }

        return listOf(assistantContextMessage) + toolResultMessages
    }

    /**
     * Collects a streaming LLM response, accumulating assistant content and tool-call deltas.
     *
     * @param context Raw conversation context sent to the LLM.
     * @param model Model used for generation.
     * @param provider Provider used for generation.
     * @param settings Chat settings applied to the request.
     * @param apiKey Optional provider API key.
     * @param tools Enabled tools available for the request.
     * @param systemMessage Composed system prompt (single source of truth), or null when absent.
     * @param onContentDelta Callback for assistant text deltas.
     * @param onToolCallChunk Callback for streamed tool-call chunks.
     * @param onReasoningChunk Callback for the completed, opaque reasoning item emitted by the provider.
     * @param onStreamComplete Callback invoked after the provider signals stream completion.
     * @param onError Callback for streaming errors.
     * @param onCancellation Callback used to persist partial content on cancellation.
     */
    private suspend fun handleLlmStreaming(
        context: List<RawChatMessage>,
        model: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?,
        controlSignal: TurnControlSignal,
        onContentDelta: suspend (deltaContent: String) -> Unit,
        onToolCallChunk: suspend (toolCallChunk: LLMStreamChunk.ToolCallChunk) -> Unit,
        onReasoningChunk: suspend (reasoningDone: LLMStreamChunk.ReasoningDone) -> Unit,
        onStreamComplete: suspend (
            finalContent: String,
            toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
            finishReason: String?
        ) -> Unit,
        onError: suspend (error: LLMCompletionError) -> Unit,
        onCancellation: suspend (partialContent: String) -> Unit
    ) {
        val accumulatedContent = StringBuilder()
        var contentTruncated = false
        var finishReason: String? = null
        val toolCallsByIndex = mutableMapOf<Int, MutableToolCallAccumulator>()

        try {
            llmApiClient.completeChatStreaming(context, model, provider, settings, apiKey, tools, systemMessage)
                .collect { llmStreamChunkEither ->
                    if (controlSignal.isCancelled) return@collect
                    llmStreamChunkEither.fold(
                        ifLeft = { llmError ->
                            logger.error("LLM API streaming error, provider ${provider.name}: $llmError")
                            onError(llmError)
                        },
                        ifRight = { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.ContentChunk -> {
                                    val remainingChars = MAX_ASSISTANT_MESSAGE_CHARS - accumulatedContent.length
                                    if (remainingChars > 0) {
                                        val allowedDelta = chunk.deltaContent.take(remainingChars)
                                        if (allowedDelta.length < chunk.deltaContent.length) {
                                            contentTruncated = true
                                        }
                                        accumulatedContent.append(allowedDelta)
                                        if (allowedDelta.isNotEmpty()) {
                                            onContentDelta(allowedDelta)
                                        }
                                        if (chunk.finishReason != null) {
                                            finishReason = chunk.finishReason
                                        }
                                    } else {
                                        contentTruncated = true
                                    }
                                }

                                is LLMStreamChunk.ToolCallChunk -> {
                                    val index = chunk.index ?: 0
                                    if (index >= MAX_TOOL_CALLS_PER_STEP) {
                                        return@fold
                                    }
                                    val accumulator = toolCallsByIndex.getOrPut(index) {
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
                                    if (chunk.argumentsDelta != null) {
                                        val remaining = MAX_TOOL_CALL_ARGUMENT_CHARS - accumulator.arguments.length
                                        if (remaining > 0) {
                                            val allowedDelta = chunk.argumentsDelta.take(remaining)
                                            accumulator.arguments.append(allowedDelta)
                                            if (allowedDelta.isNotEmpty()) {
                                                onToolCallChunk(chunk.copy(argumentsDelta = allowedDelta))
                                            }
                                        }
                                    } else {
                                        onToolCallChunk(chunk)
                                    }
                                }

                                is LLMStreamChunk.ToolCallDone -> {
                                    // The provider's authoritative final function call. It may carry a
                                    // corrected arguments string (providers can fix up the raw delta stream),
                                    // so override the delta-accumulated accumulator for this output_index.
                                    // This chunk is optional (only the Responses dialect emits it); when it is
                                    // absent, the delta-accumulated values are used unchanged.
                                    val index = chunk.index ?: 0
                                    if (index >= MAX_TOOL_CALLS_PER_STEP) {
                                        return@fold
                                    }
                                    toolCallsByIndex[index] = MutableToolCallAccumulator(
                                        id = chunk.id,
                                        name = chunk.name,
                                        arguments = StringBuilder(
                                            chunk.arguments?.take(MAX_TOOL_CALL_ARGUMENT_CHARS) ?: ""
                                        )
                                    )
                                    // The live-UI deltas were already streamed via ToolCallChunk; this chunk
                                    // only corrects the authoritative payload used for execution/persistence.
                                }

                                is LLMStreamChunk.UsageChunk -> {
                                    logger.debug(
                                        "Usage stats: prompt=${chunk.promptTokens}, completion=${chunk.completionTokens}, total=${chunk.totalTokens}, reasoning=${chunk.reasoningTokens}"
                                    )
                                }

                                is LLMStreamChunk.ReasoningDone -> {
                                    // Reasoning items are opaque and forwarded as-is so the caller can
                                    // accumulate and persist them for replay; they are never rendered.
                                    onReasoningChunk(chunk)
                                }

                                is LLMStreamChunk.ReasoningTextChunk -> {
                                    // Plaintext reasoning deltas are intended for live UI rendering and are
                                    // not part of the persisted transcript. When no live-rendering consumer
                                    // is wired, they carry no side effect here. Never persist or replay them.
                                    logger.trace(
                                        "Reasoning text delta discarded (no UI consumer): index=${chunk.outputIndex}, " +
                                                "contentIndex=${chunk.contentIndex}, delta=${chunk.delta.take(200)}"
                                    )
                                }

                                LLMStreamChunk.Done -> {
                                    val toolCallRequests = if (toolCallsByIndex.isNotEmpty()) {
                                        toolCallsByIndex.values.map { accumulator ->
                                            LLMCompletionResult.CompletionChoice.ToolCallRequest(
                                                name = accumulator.name,
                                                arguments = accumulator.arguments.toString().takeIf { it.isNotEmpty() },
                                                toolCallId = accumulator.id
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    }

                                    if (finishReason == null && toolCallRequests.isNotEmpty()) {
                                        finishReason = "tool_calls"
                                    }

                                    val finalContent = accumulatedContent.toString() +
                                            if (contentTruncated) ASSISTANT_TRUNCATION_NOTICE else ""
                                    onStreamComplete(finalContent, toolCallRequests, finishReason)
                                }

                                is LLMStreamChunk.Error -> {
                                    logger.error("LLM API returned streaming error chunk: ${chunk.llmError}")
                                    onError(chunk.llmError)
                                }
                            }
                        }
                    )
                }
        } catch (cancellationException: CancellationException) {
            logger.info("LLM streaming cancelled, accumulated content length: ${accumulatedContent.length}")
            try {
                withContext(NonCancellable) {
                    onCancellation(
                        accumulatedContent.toString() +
                                if (contentTruncated) ASSISTANT_TRUNCATION_NOTICE else ""
                    )
                }
            } catch (handlerError: Exception) {
                logger.error("Failed to run onCancellation handler: ${handlerError.message}", handlerError)
            }
            throw cancellationException
        }
    }

    /**
     * Carries the initial loop state after user persistence and context reconstruction.
     *
     * @property lastMessageId Message that anchors the next assistant reply.
     * @property conversationContext Reconstructed identity-bearing source context for the turn.
     */
    private data class PreparedTurnState(
        val lastMessageId: Long,
        val conversationContext: ConversationContext
    )

    /**
     * Carries the assistant step result needed by the shared tool loop.
     *
     * @property assistantMessage Persisted assistant message for the current iteration.
     * @property assistantContent Assistant content that should be appended back into LLM context.
     * @property toolCallRequests Tool calls requested by the assistant.
     * @property reasoningItems Replay-safe reasoning items emitted with the assistant step, forwarded so the
     *            next follow-up LLM request can replay chain-of-thought. Opaque payload; never logged or rendered.
     */
    private data class AssistantStepOutcome(
        val assistantMessage: ChatMessage.AssistantMessage,
        val assistantContent: String?,
        val toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        val reasoningItems: List<JsonObject>? = null
    )

    /**
     * Mutable accumulator used while reconstructing tool calls from streaming deltas.
     *
     * @property id Provider tool-call identifier as soon as it becomes available.
     * @property name Tool name once emitted by the provider.
     * @property arguments Incrementally accumulated arguments payload.
     */
    private data class MutableToolCallAccumulator(
        var id: String?,
        var name: String,
        val arguments: StringBuilder
    )
}