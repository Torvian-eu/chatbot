package eu.torvian.chatbot.server.service.core.chat.persistence

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult

/**
 * Persists the message and tool-call state transitions that occur during a single conversation turn.
 */
interface ConversationTurnPersistence {
    /**
     * Persists a new user message, advances the session leaf pointer, and refreshes the optional parent.
     *
     * @param sessionId Session receiving the user message.
     * @param content Raw user content.
     * @param parentMessageId Optional parent message for threaded continuation.
     * @param fileReferences File references attached to the user message.
     * @return Saved user message and the refreshed parent, when one exists.
     */
    suspend fun saveUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        fileReferences: List<FileReference> = emptyList()
    ): PersistedUserMessage

    /**
     * Persists a new assistant message, advances the session leaf pointer, and refreshes the parent message.
     *
     * @param sessionId Session receiving the assistant message.
     * @param content Assistant content to persist.
     * @param parentMessageId Parent message that the assistant replies to.
     * @param model Model metadata associated with the assistant message.
     * @param settings Settings metadata associated with the assistant message.
     * @return Saved assistant message and the refreshed parent message.
     */
    suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long,
        model: LLMModel,
        settings: ChatModelSettings
    ): PersistedAssistantMessage

    /**
     * Persists the latest accumulated content for an assistant message.
     *
     * @param messageId Assistant message to update.
     * @param content Final or partial accumulated content.
     * @return Updated assistant message.
     */
    suspend fun updateAssistantMessageContent(
        messageId: Long,
        content: String
    ): ChatMessage.AssistantMessage

    /**
     * Persists tool-call requests emitted by the assistant for the current iteration.
     *
     * @param messageId Assistant message that owns the tool calls.
     * @param toolCallRequests Tool-call requests emitted by the LLM.
     * @param enabledTools Enabled tool definitions available for the turn.
     * @return Persisted tool-call records in their initial statuses.
     */
    suspend fun persistPendingToolCalls(
        messageId: Long,
        toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        enabledTools: List<ToolDefinition>?
    ): List<ToolCall>

    /**
     * Loads all persisted tool calls for the session so the next LLM request can rebuild context.
     *
     * @param sessionId Session whose tool calls should be loaded.
     * @return Persisted tool calls ordered for deterministic context reconstruction.
     */
    suspend fun loadSessionToolCalls(sessionId: Long): List<ToolCall>
}

/**
 * Carries the result of persisting a user message.
 *
 * @property userMessage Newly saved user message.
 * @property updatedParentMessage Refreshed parent after child linkage, when a parent existed.
 */
data class PersistedUserMessage(
    val userMessage: ChatMessage.UserMessage,
    val updatedParentMessage: ChatMessage?
)

/**
 * Carries the result of persisting an assistant message.
 *
 * @property assistantMessage Newly saved assistant message.
 * @property updatedParentMessage Refreshed parent after child linkage.
 */
data class PersistedAssistantMessage(
    val assistantMessage: ChatMessage.AssistantMessage,
    val updatedParentMessage: ChatMessage
)