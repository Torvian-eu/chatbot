package eu.torvian.chatbot.server.service.core.chat.persistence

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import kotlinx.serialization.json.JsonObject

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
     * @param agentRoleId Optional agent role associated with the assistant message (provenance); null
     *                    when the message was not produced through an agent role.
     * @param reasoningItems Optional replay-safe reasoning items emitted with the assistant message. Must be
     *                       `null` for non-reasoning models; callers must sanitize them before persistence.
     *                       Opaque, never logged or rendered.
     * @param completion Completion state written together with the row. The streaming placeholder is inserted
     *                   with [AssistantMessageCompletionState.InFlight] (not completed, no cause yet), a
     *                   non-streaming answer with an explicit terminal state, and the default
     *                   [AssistantMessageCompletionState.Completed] covers manually inserted messages.
     * @return Saved assistant message and the refreshed parent message.
     */
    suspend fun saveAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long,
        model: LLMModel,
        settings: ModelSettings,
        agentRoleId: Long? = null,
        reasoningItems: List<JsonObject>? = null,
        completion: AssistantMessageCompletionState = AssistantMessageCompletionState.Completed
    ): PersistedAssistantMessage

    /**
     * Persists the latest accumulated content for an assistant message.
     *
     * This is the turn-finalization write: content and completion state are always written together, so the
     * accumulated partial text of an abnormal ending is stored in the same statement that records why the
     * generation stopped. Unlike the public edit path, this call never clears the state implicitly: the
     * completion state it writes is the one the caller identified.
     *
     * @param messageId Assistant message to update.
     * @param content Final or partial accumulated content.
     * @param completion Terminal (or in-flight) completion state to persist with [content].
     * @return Updated assistant message.
     */
    suspend fun updateAssistantMessageContent(
        messageId: Long,
        content: String,
        completion: AssistantMessageCompletionState = AssistantMessageCompletionState.Completed
    ): ChatMessage.AssistantMessage

    /**
     * Persists the reasoning items attached to an existing assistant message.
     *
     * @param messageId Assistant message to update.
     * @param reasoningItems Replay-safe reasoning items to persist. `null` clears any stored reasoning.
     *                       Opaque payload; never logged or rendered.
     * @return Updated assistant message.
     */
    suspend fun updateAssistantMessageReasoning(
        messageId: Long,
        reasoningItems: List<JsonObject>?
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