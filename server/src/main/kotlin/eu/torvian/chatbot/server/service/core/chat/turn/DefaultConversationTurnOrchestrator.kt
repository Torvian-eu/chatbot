package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
 */
class DefaultConversationTurnOrchestrator(
    private val llmApiClient: LLMApiClient,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val toolResultContentBuilder: ToolResultContentBuilder,
    private val chatContextBuilder: ChatContextBuilder,
    private val conversationTurnPersistence: ConversationTurnPersistence,
) : ConversationTurnOrchestrator {

    companion object {
        /** Logger used for turn-runtime diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultConversationTurnOrchestrator::class.java)

        /** Upper bound that prevents an unbounded assistant/tool loop. */
        private const val MAX_TOOL_CALLING_ITERATIONS: Int = 100
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
        var currentContext = preparedTurn.currentContext
        var iterationCount = 0

        while (iterationCount < MAX_TOOL_CALLING_ITERATIONS) {
            iterationCount++
            val assistantStep = processAssistantStep(request, currentContext, lastMessageId, emit) ?: break
            lastMessageId = assistantStep.assistantMessage.id

            val pendingToolCalls = conversationTurnPersistence.persistPendingToolCalls(
                messageId = assistantStep.assistantMessage.id,
                toolCallRequests = assistantStep.toolCallRequests,
                enabledTools = request.llmConfig.tools
            )
            emit(ConversationTurnEvent.ToolCallsReceived(pendingToolCalls))

            val completedToolCalls = executeToolCalls(request, pendingToolCalls, emit)
            currentContext = appendAssistantAndToolResults(
                currentContext = currentContext,
                assistantContent = assistantStep.assistantContent,
                toolCallRequests = assistantStep.toolCallRequests,
                completedToolCalls = completedToolCalls
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
        val currentContext = chatContextBuilder.buildContext(
            startingMessageId = lastMessageId,
            sessionMessages = updatedSessionMessages,
            toolCalls = sessionToolCalls
        )

        return PreparedTurnState(lastMessageId = lastMessageId, currentContext = currentContext)
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
        val llmCompletionResult = llmApiClient.completeChat(
            messages = currentContext,
            modelConfig = request.llmConfig.model,
            provider = request.llmConfig.provider,
            settings = request.llmConfig.settings,
            apiKey = request.llmConfig.apiKey,
            tools = request.llmConfig.tools
        ).getOrElse { error ->
            logger.error("LLM API call failed for session ${request.session.id}: $error")
            emit(ConversationTurnEvent.ExternalServiceError(error))
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        logger.info("LLM API call successful for session ${request.session.id}")

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

        val assistantMessage = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = choice.content ?: "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings
        ).let { persistedAssistantMessage ->
            emit(
                ConversationTurnEvent.AssistantMessageSaved(
                    persistedAssistantMessage.assistantMessage,
                    persistedAssistantMessage.updatedParentMessage
                )
            )
            persistedAssistantMessage.assistantMessage
        }

        if (choice.finishReason != "tool_calls" || choice.toolCalls.isNullOrEmpty()) {
            emit(ConversationTurnEvent.TurnCompleted)
            return null
        }

        return AssistantStepOutcome(
            assistantMessage = assistantMessage,
            assistantContent = choice.content,
            toolCallRequests = choice.toolCalls
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
        val assistantMessage = conversationTurnPersistence.saveAssistantMessage(
            sessionId = request.session.id,
            content = "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings
        ).let { persistedAssistantMessage ->
            emit(
                ConversationTurnEvent.AssistantMessageStarted(
                    persistedAssistantMessage.assistantMessage,
                    persistedAssistantMessage.updatedParentMessage
                )
            )
            persistedAssistantMessage.assistantMessage
        }

        var assistantStepOutcome: AssistantStepOutcome? = null
        handleLlmStreaming(
            context = currentContext,
            model = request.llmConfig.model,
            provider = request.llmConfig.provider,
            settings = request.llmConfig.settings,
            apiKey = request.llmConfig.apiKey,
            tools = request.llmConfig.tools,
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
            onStreamComplete = { finalContent, toolCallRequests, finishReason ->
                val updatedAssistantMessage = conversationTurnPersistence.updateAssistantMessageContent(
                    messageId = assistantMessage.id,
                    content = finalContent
                )
                emit(ConversationTurnEvent.AssistantMessageFinished(updatedAssistantMessage))

                if (finishReason != "tool_calls" || toolCallRequests.isEmpty()) {
                    emit(ConversationTurnEvent.TurnCompleted)
                    assistantStepOutcome = null
                } else {
                    assistantStepOutcome = AssistantStepOutcome(
                        assistantMessage = updatedAssistantMessage,
                        assistantContent = updatedAssistantMessage.content,
                        toolCallRequests = toolCallRequests
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
        toolCallOrchestrator.executeAndUpdateToolCalls(
            request.userId,
            pendingToolCalls,
            request.llmConfig.tools,
            request.toolApprovalFlow
        ).collect { event ->
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
            }
        }
        return completedToolCalls
    }

    /**
     * Extends the LLM context with the assistant tool-call request and the resulting tool outputs.
     *
     * @param currentContext Context accumulated so far for the turn.
     * @param assistantContent Assistant content associated with the tool-call request.
     * @param toolCallRequests Tool calls requested by the assistant.
     * @param completedToolCalls Completed tool calls whose results should be appended.
     * @return Updated raw context used for the next assistant iteration.
     */
    private fun appendAssistantAndToolResults(
        currentContext: List<RawChatMessage>,
        assistantContent: String?,
        toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        completedToolCalls: List<ToolCall>
    ): List<RawChatMessage> {
        val assistantContextMessage = RawChatMessage.Assistant(
            content = assistantContent,
            toolCalls = toolCallRequests.map { toolCallRequest ->
                RawChatMessage.Assistant.ToolCall(
                    id = toolCallRequest.toolCallId,
                    name = toolCallRequest.name,
                    arguments = toolCallRequest.arguments
                )
            }
        )
        val toolResultMessages = completedToolCalls.map { toolCall ->
            RawChatMessage.Tool(
                content = toolResultContentBuilder.build(toolCall),
                toolCallId = toolCall.toolCallId ?: "",
                name = toolCall.toolName
            )
        }

        return currentContext + assistantContextMessage + toolResultMessages
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
     * @param onContentDelta Callback for assistant text deltas.
     * @param onToolCallChunk Callback for streamed tool-call chunks.
     * @param onStreamComplete Callback invoked after the provider signals stream completion.
     * @param onError Callback for streaming errors.
     * @param onCancellation Callback used to persist partial content on cancellation.
     */
    private suspend fun handleLlmStreaming(
        context: List<RawChatMessage>,
        model: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        onContentDelta: suspend (deltaContent: String) -> Unit,
        onToolCallChunk: suspend (toolCallChunk: LLMStreamChunk.ToolCallChunk) -> Unit,
        onStreamComplete: suspend (
            finalContent: String,
            toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
            finishReason: String?
        ) -> Unit,
        onError: suspend (error: LLMCompletionError) -> Unit,
        onCancellation: suspend (partialContent: String) -> Unit
    ) {
        val accumulatedContent = StringBuilder()
        var finishReason: String? = null
        val toolCallsByIndex = mutableMapOf<Int, MutableToolCallAccumulator>()

        try {
            llmApiClient.completeChatStreaming(context, model, provider, settings, apiKey, tools)
                .collect { llmStreamChunkEither ->
                    llmStreamChunkEither.fold(
                        ifLeft = { llmError ->
                            logger.error("LLM API streaming error, provider ${provider.name}: $llmError")
                            onError(llmError)
                        },
                        ifRight = { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.ContentChunk -> {
                                    accumulatedContent.append(chunk.deltaContent)
                                    onContentDelta(chunk.deltaContent)
                                    if (chunk.finishReason != null) {
                                        finishReason = chunk.finishReason
                                    }
                                }

                                is LLMStreamChunk.ToolCallChunk -> {
                                    val index = chunk.index ?: 0
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
                                        accumulator.arguments.append(chunk.argumentsDelta)
                                    }

                                    onToolCallChunk(chunk)
                                }

                                is LLMStreamChunk.UsageChunk -> {
                                    logger.debug(
                                        "Usage stats: prompt=${chunk.promptTokens}, completion=${chunk.completionTokens}, total=${chunk.totalTokens}"
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

                                    onStreamComplete(accumulatedContent.toString(), toolCallRequests, finishReason)
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
                    onCancellation(accumulatedContent.toString())
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
     * @property currentContext Reconstructed raw chat context for the next LLM request.
     */
    private data class PreparedTurnState(
        val lastMessageId: Long,
        val currentContext: List<RawChatMessage>
    )

    /**
     * Carries the assistant step result needed by the shared tool loop.
     *
     * @property assistantMessage Persisted assistant message for the current iteration.
     * @property assistantContent Assistant content that should be appended back into LLM context.
     * @property toolCallRequests Tool calls requested by the assistant.
     */
    private data class AssistantStepOutcome(
        val assistantMessage: ChatMessage.AssistantMessage,
        val assistantContent: String?,
        val toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>
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