package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.chat.reasoning.appendReasoningTextDelta
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Applies one turn event to the cached transcript and tool calls of its session.
 *
 * Both transports (the non-streaming and the streaming socket) reach the same arms here, so the transcript rules are
 * written once. The applier touches nothing else: the instant of the message an event persisted is returned to the
 * caller instead of being written here, which keeps the session list out of the event path.
 *
 * @property details The cached transcripts the applied events fill.
 * @property toolCalls The cached tool calls the applied events fill.
 */
internal class SessionEventApplier(
    private val details: SessionDetailsStore,
    private val toolCalls: ToolCallStore
) {

    /**
     * Applies a non-streaming [event] to the cached session of [sessionId].
     *
     * @param sessionId The session the event belongs to.
     * @param event The event to apply.
     * @return The server instant of the message the event persisted, or `null` when it persisted none.
     */
    suspend fun apply(sessionId: Long, event: ChatEvent): Instant? {
        when (event) {
            is ChatEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.userMessage.id}")
                details.mutate(sessionId) { session ->
                    session.withPersistedUserMessage(event.userMessage, event.updatedParentMessage)
                }
            }

            is ChatEvent.AssistantMessageSaved -> {
                logger.debug("Assistant message saved: ${event.assistantMessage.id}")
                details.mutate(sessionId) { session ->
                    session.withAppendedAssistantMessage(event.assistantMessage, event.updatedParentMessage)
                }
            }

            is ChatEvent.ToolCallsReceived -> {
                logger.debug("Tool calls received: ${event.toolCalls.size} calls")
                addToolCalls(sessionId, event.toolCalls)
            }

            is ChatEvent.ToolCallApprovalRequested -> {
                logger.debug("Tool call approval requested: ${event.toolCall.toolName}")
                // The call is cached in its AWAITING_APPROVAL status so the UI can display it
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatEvent.ToolCallExecuting -> {
                logger.debug("Tool call executing: ${event.toolCall.toolName}")
                // The call is cached in its EXECUTING status so the UI can display it
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatEvent.ToolExecutionCompleted -> {
                logger.debug("Tool execution completed: ${event.toolCall.toolName} - ${event.toolCall.status}")
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatEvent.ErrorOccurred -> {
                logger.error("Chat event error: ${event.error}")
            }

            is ChatEvent.StreamCompleted -> {
                logger.info("Event stream completed for session $sessionId")
            }

            is ChatEvent.OperatorToolExecutionRequested -> {
                // The operator executor drives the spawned conversation on its own second WebSocket and reports the
                // result back through the chat socket, so no session data is derived from this event.
                logger.debug("Operator tool execution requested: toolCallId=${event.toolCallId}, name=${event.toolName}")
            }

            is ChatEvent.CompactionCompleted -> {
                // The compaction notification is informational: it must never create, replace or mutate transcript
                // messages, so the session cache is intentionally left untouched.
                logger.debug(
                    "Compaction notification for session $sessionId: chunk ${event.payload.chunkId} " +
                        "covers ${event.payload.coveredMessageIds.size} messages"
                )
            }

            is ChatEvent.RawLlmRequest -> {
                // The captured provider request is a diagnostic that must never create, replace or mutate transcript
                // messages, so the session cache is intentionally left untouched. The chat state holds it for the
                // request viewer.
                logger.debug(
                    "Raw LLM request for session $sessionId: ${event.payload.method} ${event.payload.url}"
                )
            }
        }
        return event.persistedMessageOrNull()?.updatedAt
    }

    /**
     * Applies a streaming [event] to the cached session of [sessionId].
     *
     * @param sessionId The session the event belongs to.
     * @param event The event to apply.
     * @return The server instant of the message the event persisted, or `null` when it persisted none.
     */
    suspend fun apply(sessionId: Long, event: ChatStreamEvent): Instant? {
        when (event) {
            is ChatStreamEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.userMessage.id}")
                details.mutate(sessionId) { session ->
                    session.withPersistedUserMessage(event.userMessage, event.updatedParentMessage)
                }
            }

            is ChatStreamEvent.AssistantMessageStart -> {
                logger.debug("Assistant message started: ${event.assistantMessage.id}")
                details.mutate(sessionId) { session ->
                    session.withAppendedAssistantMessage(event.assistantMessage, event.updatedParentMessage)
                }
            }

            is ChatStreamEvent.AssistantMessageDelta -> {
                logger.trace("Assistant message delta: ${event.deltaContent.length} chars")
                details.mutate(sessionId) { session ->
                    session.copy(
                        messages = session.messages.map { message ->
                            if (message.id == event.messageId) {
                                val newContent = message.content + event.deltaContent
                                (message as ChatMessage.AssistantMessage)
                                    .copy(content = newContent, updatedAt = Clock.System.now())
                            } else {
                                message
                            }
                        }
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageReasoningDelta -> {
                logger.trace("Assistant message reasoning delta: ${event.deltaContent.length} chars")
                // Live reasoning is appended in the same item shape the server persists, so the reasoning section
                // renders from one place both while streaming and after the completing event replaces the message.
                details.mutate(sessionId) { session ->
                    session.copy(
                        messages = session.messages.map { message ->
                            // A missing or non-assistant message is tolerated like in the other arms: the delta is
                            // about a placeholder that is not cached yet, so there is nothing to update.
                            val assistantMessage = message as? ChatMessage.AssistantMessage
                            if (assistantMessage != null && message.id == event.messageId) {
                                assistantMessage.copy(
                                    reasoningItems = assistantMessage.reasoningItems
                                        .appendReasoningTextDelta(event.deltaContent),
                                    updatedAt = Clock.System.now()
                                )
                            } else {
                                message
                            }
                        }
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageEnd -> {
                logger.info("Assistant message completed: ${event.assistantMessage.id}")
                details.mutate(sessionId) { session ->
                    session.withReplacedAssistantMessage(event.assistantMessage)
                }
            }

            is ChatStreamEvent.ToolCallDelta -> {
                logger.trace("Tool call delta: ${event.name}")
                // Deliberately not applied to the tool-call cache: only the complete call set is displayable, and the
                // partial chunks are already fed to the chat state by the turn collector, which uses their arrival
                // instant for the construction indicator.
            }

            is ChatStreamEvent.ToolCallsReceived -> {
                logger.debug("Tool calls received: ${event.toolCalls.size} calls")
                addToolCalls(sessionId, event.toolCalls)
            }

            is ChatStreamEvent.ToolCallApprovalRequested -> {
                logger.debug("Tool call approval requested: ${event.toolCall.toolName}")
                // The call is cached in its AWAITING_APPROVAL status so the UI can display it
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatStreamEvent.ToolCallExecuting -> {
                logger.debug("Tool call executing: ${event.toolCall.toolName}")
                // The call is cached in its EXECUTING status so the UI can display it
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatStreamEvent.ToolExecutionCompleted -> {
                logger.debug("Tool execution completed: ${event.toolCall.toolName}")
                replaceToolCall(sessionId, event.toolCall)
            }

            is ChatStreamEvent.ErrorOccurred -> {
                logger.error("Streaming error: ${event.error}")
            }

            is ChatStreamEvent.StreamCompleted -> {
                logger.info("Streaming completed for session $sessionId")
            }

            is ChatStreamEvent.OperatorToolExecutionRequested -> {
                // The operator executor drives the spawned conversation on its own second WebSocket and reports the
                // result back through the chat socket, so no session data is derived from this event.
                logger.debug("Operator tool execution requested: toolCallId=${event.toolCallId}, name=${event.toolName}")
            }

            is ChatStreamEvent.CompactionCompleted -> {
                // The compaction notification is informational: it must never create, replace or mutate transcript
                // messages, so the session cache is intentionally left untouched.
                logger.debug(
                    "Compaction notification for session $sessionId: chunk ${event.payload.chunkId} " +
                        "covers ${event.payload.coveredMessageIds.size} messages"
                )
            }

            is ChatStreamEvent.RawLlmRequest -> {
                // The captured provider request is a diagnostic that must never create, replace or mutate transcript
                // messages, so the session cache is intentionally left untouched. The chat state holds it for the
                // request viewer.
                logger.debug(
                    "Raw LLM request for session $sessionId: ${event.payload.method} ${event.payload.url}"
                )
            }
        }
        return event.persistedMessageOrNull()?.updatedAt
    }

    /**
     * Stores [calls] in the tool-call cache under the id of the message they belong to.
     *
     * An empty list names no message and no key to store under, so it leaves the cache as it is.
     *
     * @param sessionId The ID of the session containing the tool calls.
     * @param calls The complete tool-call list of one message.
     */
    private suspend fun addToolCalls(sessionId: Long, calls: List<ToolCall>) {
        toolCalls.mutate(sessionId) { sessionToolCalls ->
            val messageId = calls.firstOrNull()?.messageId
            if (messageId == null) {
                logger.warn("Received empty list of tool calls")
                sessionToolCalls
            } else {
                sessionToolCalls + (messageId to calls)
            }
        }
    }

    /**
     * Replaces the cached entry of [toolCall] with the call's new state, typically once its execution settled.
     *
     * @param sessionId The ID of the session containing the tool call.
     * @param toolCall The updated tool call to replace the existing one in the cache.
     */
    private suspend fun replaceToolCall(sessionId: Long, toolCall: ToolCall) {
        toolCalls.mutate(sessionId) { sessionToolCalls ->
            val messageToolCalls = sessionToolCalls[toolCall.messageId] ?: emptyList()
            val updatedMessageToolCalls = messageToolCalls.map { if (it.id == toolCall.id) toolCall else it }
            sessionToolCalls + (toolCall.messageId to updatedMessageToolCalls)
        }
    }

    companion object {
        private val logger = kmpLogger<SessionEventApplier>()
    }
}

/**
 * Returns the transcript with [message] recorded as the persisted user message, which becomes the active leaf.
 *
 * @receiver The cached session.
 * @param message The user message the server persisted.
 * @param updatedParent The parent that must be replaced by its updated version, or `null` when it did not change.
 */
private fun ChatSession.withPersistedUserMessage(
    message: ChatMessage.UserMessage,
    updatedParent: ChatMessage?
): ChatSession {
    val messages = if (updatedParent == null) {
        this.messages + message
    } else {
        this.messages.map { msg -> if (msg.id == updatedParent.id) updatedParent else msg } + message
    }
    return copy(messages = messages, currentLeafMessageId = message.id)
}

/**
 * Returns the transcript with [message] appended as the assistant reply that becomes the active leaf.
 *
 * @receiver The cached session.
 * @param message The assistant message the server persisted.
 * @param updatedParent The parent that must be replaced by its updated version.
 */
private fun ChatSession.withAppendedAssistantMessage(
    message: ChatMessage.AssistantMessage,
    updatedParent: ChatMessage
): ChatSession = copy(
    messages = this.messages.map { msg -> if (msg.id == updatedParent.id) updatedParent else msg } + message,
    currentLeafMessageId = message.id
)

/**
 * Returns the transcript with [message] substituted for the placeholder it finalizes.
 *
 * @receiver The cached session.
 * @param message The assistant message the server persisted.
 */
private fun ChatSession.withReplacedAssistantMessage(message: ChatMessage.AssistantMessage): ChatSession = copy(
    messages = this.messages.map { msg -> if (msg.id == message.id) message else msg },
    currentLeafMessageId = message.id
)

/**
 * Returns the transcript message this event persisted, or `null` when the event persisted none.
 *
 * It is the single definition of what counts as session activity, so the arms that carry a persisted message are not
 * repeated in the applier.
 */
private fun ChatEvent.persistedMessageOrNull(): ChatMessage? = when (this) {
    is ChatEvent.UserMessageSaved -> userMessage
    is ChatEvent.AssistantMessageSaved -> assistantMessage
    else -> null
}

/**
 * Returns the transcript message this event persisted, or `null` when the event persisted none.
 *
 * It is the single definition of what counts as session activity, so the arms that carry a persisted message are not
 * repeated in the applier.
 */
private fun ChatStreamEvent.persistedMessageOrNull(): ChatMessage? = when (this) {
    is ChatStreamEvent.UserMessageSaved -> userMessage
    is ChatStreamEvent.AssistantMessageStart -> assistantMessage
    is ChatStreamEvent.AssistantMessageEnd -> assistantMessage
    else -> null
}
