package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
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
import kotlin.time.Clock

/**
 * Default implementation that owns the shared assistant/tool loop for a single conversation turn.
 *
 * @property messageDao DAO used to persist chat messages.
 * @property sessionDao DAO used to update the session leaf pointer.
 * @property llmApiClient Client used for streaming and non-streaming LLM calls.
 * @property toolCallDao DAO used to persist tool-call records.
 * @property toolCallOrchestrator Collaborator that handles approval and tool execution.
 * @property transactionScope Transaction wrapper for atomic persistence operations.
 * @property toolResultContentBuilder Serializer for completed tool results appended back into context.
 * @property chatContextBuilder Builder that reconstructs the threaded LLM context.
 */
class DefaultConversationTurnOrchestrator(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val llmApiClient: LLMApiClient,
    private val toolCallDao: ToolCallDao,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val transactionScope: TransactionScope,
    private val toolResultContentBuilder: ToolResultContentBuilder,
    private val chatContextBuilder: ChatContextBuilder,
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

            val pendingToolCalls = persistPendingToolCalls(
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
            val userMessage = saveUserMessage(
                sessionId = request.session.id,
                content = request.content,
                parentMessageId = request.parentMessageId,
                fileReferences = request.fileReferences
            ).let { (savedMessage, updatedParentMessage) ->
                emit(ConversationTurnEvent.UserMessageSaved(savedMessage, updatedParentMessage))
                savedMessage
            }
            lastMessageId = userMessage.id
            request.session.messages + userMessage
        } else {
            val parentMessageId = request.parentMessageId
                ?: throw IllegalStateException("parentMessageId is null in Branch & Continue mode")
            lastMessageId = parentMessageId
            request.session.messages
        }

        val sessionToolCalls = toolCallDao.getToolCallsBySessionId(request.session.id).sortedBy { it.id }
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

        val assistantMessage = saveAssistantMessage(
            sessionId = request.session.id,
            content = choice.content ?: "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings
        ).let { (savedMessage, updatedParentMessage) ->
            emit(ConversationTurnEvent.AssistantMessageSaved(savedMessage, updatedParentMessage))
            savedMessage
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
        val assistantMessage = saveAssistantMessage(
            sessionId = request.session.id,
            content = "",
            parentMessageId = parentMessageId,
            model = request.llmConfig.model,
            settings = request.llmConfig.settings
        ).let { (savedMessage, updatedParentMessage) ->
            emit(ConversationTurnEvent.AssistantMessageStarted(savedMessage, updatedParentMessage))
            savedMessage
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
                val updatedAssistantMessage = updateAssistantMessageContent(
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
                    updateAssistantMessageContent(
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
     * Persists tool-call requests received from the LLM.
     *
     * @param messageId Assistant message that owns the tool calls.
     * @param toolCallRequests Tool-call requests emitted by the LLM.
     * @param enabledTools Enabled tool definitions available for the turn.
     * @return Persisted tool-call records in their initial statuses.
     */
    private suspend fun persistPendingToolCalls(
        messageId: Long,
        toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        enabledTools: List<ToolDefinition>?
    ): List<ToolCall> {
        return toolCallRequests.map { toolCallRequest ->
            val toolDefinition = enabledTools?.find { it.name == toolCallRequest.name }
            toolCallDao.insertToolCall(
                messageId = messageId,
                toolDefinitionId = toolDefinition?.id,
                toolName = toolCallRequest.name,
                toolCallId = toolCallRequest.toolCallId,
                input = toolCallRequest.arguments,
                output = null,
                status = if (toolDefinition == null) ToolCallStatus.ERROR else ToolCallStatus.PENDING,
                errorMessage = if (toolDefinition == null) {
                    "Tool '${toolCallRequest.name}' not found in enabled tools"
                } else {
                    null
                },
                denialReason = null,
                executedAt = Clock.System.now(),
                durationMs = null
            ).getOrElse { error ->
                throw IllegalStateException("Failed to insert tool call: $error")
            }
        }
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
     * Saves a new user message and updates the session leaf and optional parent linkage.
     *
     * @param sessionId Session receiving the user message.
     * @param content Raw user content.
     * @param parentMessageId Optional parent message for threaded continuation.
     * @param fileReferences File references attached to the user message.
     * @return Pair of the saved user message and the refreshed parent message, when present.
     */
    private suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        fileReferences: List<eu.torvian.chatbot.common.models.core.FileReference> = emptyList()
    ): Pair<ChatMessage.UserMessage, ChatMessage?> = transactionScope.transaction {
        val userMessage = messageDao.insertMessage(
            sessionId = sessionId,
            targetMessageId = parentMessageId,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = content,
            modelId = null,
            settingsId = null,
            fileReferences = fileReferences
        ).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert user message. Session id: $sessionId. " +
                    "Parent message id: $parentMessageId. Error: $daoError"
            )
        } as ChatMessage.UserMessage

        sessionDao.updateSessionLeafMessageId(sessionId, userMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. Session id: $sessionId. " +
                    "New leaf message id: ${userMessage.id}. Error: $updateError"
            )
        }

        val updatedParentMessage = parentMessageId?.let { id ->
            messageDao.getMessageById(id).getOrElse { daoError ->
                throw IllegalStateException(
                    "Failed to retrieve updated parent message. Parent message id: $id. Error: $daoError"
                )
            }
        }

        userMessage to updatedParentMessage
    }

    /**
     * Saves a new assistant message and updates the session leaf and parent linkage.
     *
     * @param sessionId Session receiving the assistant message.
     * @param content Assistant content to persist.
     * @param parentMessageId Parent message that the assistant replies to.
     * @param model Model metadata associated with the assistant message.
     * @param settings Settings metadata associated with the assistant message.
     * @return Pair of the saved assistant message and the refreshed parent message.
     */
    private suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long,
        model: LLMModel,
        settings: ChatModelSettings
    ): Pair<ChatMessage.AssistantMessage, ChatMessage> = transactionScope.transaction {
        val assistantMessage = messageDao.insertMessage(
            sessionId = sessionId,
            targetMessageId = parentMessageId,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            modelId = model.id,
            settingsId = settings.id
        ).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to insert assistant message. Session id: $sessionId. " +
                    "Parent message id: $parentMessageId. Error: $daoError"
            )
        } as ChatMessage.AssistantMessage

        sessionDao.updateSessionLeafMessageId(sessionId, assistantMessage.id).getOrElse { updateError ->
            throw IllegalStateException(
                "Failed to update session leaf message ID. Session id: $sessionId. " +
                    "New leaf message id: ${assistantMessage.id}. Error: $updateError"
            )
        }

        val updatedParentMessage = messageDao.getMessageById(parentMessageId).getOrElse { daoError ->
            throw IllegalStateException(
                "Failed to retrieve updated parent message. Parent message id: $parentMessageId. Error: $daoError"
            )
        }

        assistantMessage to updatedParentMessage
    }

    /**
     * Persists the final content of a streaming assistant message.
     *
     * @param messageId Assistant message to update.
     * @param content Final accumulated content.
     * @return Updated assistant message.
     */
    private suspend fun updateAssistantMessageContent(
        messageId: Long,
        content: String
    ): ChatMessage.AssistantMessage = transactionScope.transaction {
        messageDao.updateMessageContent(messageId, content).getOrElse { error ->
            throw IllegalStateException("Failed to update assistant message content: $error")
        } as ChatMessage.AssistantMessage
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