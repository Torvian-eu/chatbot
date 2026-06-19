package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.llm.LLMCompletionError

/**
 * Internal lifecycle events emitted while orchestrating a single turn.
 */
sealed interface ConversationTurnEvent {
    /**
     * Signals that a new user message has been persisted.
     *
     * @property userMessage Saved user message.
     * @property updatedParentMessage Updated parent, when a parent existed.
     */
    data class UserMessageSaved(
        val userMessage: ChatMessage.UserMessage,
        val updatedParentMessage: ChatMessage?
    ) : ConversationTurnEvent

    /**
     * Signals that a non-streaming assistant message has been persisted in its final form.
     *
     * @property assistantMessage Saved assistant message.
     * @property updatedParentMessage Updated parent message after child linkage.
     */
    data class AssistantMessageSaved(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : ConversationTurnEvent

    /**
     * Signals that an empty assistant message placeholder was created for streaming output.
     *
     * @property assistantMessage Saved assistant placeholder message.
     * @property updatedParentMessage Updated parent message after child linkage.
     */
    data class AssistantMessageStarted(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : ConversationTurnEvent

    /**
     * Emits a streamed assistant content delta.
     *
     * @property messageId Assistant message receiving the delta.
     * @property deltaContent Incremental text content.
     */
    data class AssistantMessageDelta(
        val messageId: Long,
        val deltaContent: String
    ) : ConversationTurnEvent

    /**
     * Emits a streamed tool-call argument delta.
     *
     * @property messageId Assistant message that owns the tool call.
     * @property index Position of the tool call within the streamed batch.
     * @property id Provider tool-call identifier, when present.
     * @property name Tool name fragment resolved for the delta.
     * @property argumentsDelta Incremental arguments payload.
     */
    data class ToolCallDelta(
        val messageId: Long,
        val index: Int?,
        val id: String?,
        val name: String,
        val argumentsDelta: String?
    ) : ConversationTurnEvent

    /**
     * Signals that a streaming assistant message was finalized and persisted.
     *
     * @property assistantMessage Updated assistant message containing the full streamed content.
     */
    data class AssistantMessageFinished(
        val assistantMessage: ChatMessage.AssistantMessage
    ) : ConversationTurnEvent

    /**
     * Signals that pending tool calls were persisted for the current assistant response.
     *
     * @property toolCalls Saved tool-call records.
     */
    data class ToolCallsReceived(
        val toolCalls: List<ToolCall>
    ) : ConversationTurnEvent

    /**
     * Signals that a tool call requires client approval.
     *
     * @property toolCall Tool call awaiting approval.
     */
    data class ToolCallApprovalRequested(
        val toolCall: ToolCall
    ) : ConversationTurnEvent

    /**
     * Signals that a tool call has started executing.
     *
     * @property toolCall Tool call now in executing state.
     */
    data class ToolCallExecuting(
        val toolCall: ToolCall
    ) : ConversationTurnEvent

    /**
     * Signals that a tool call reached a terminal state.
     *
     * @property toolCall Completed tool call.
     */
    data class ToolExecutionCompleted(
        val toolCall: ToolCall
    ) : ConversationTurnEvent

    /**
     * Signals an LLM-facing external service failure that should surface as a chat processing error.
     *
     * @property llmError Provider-agnostic LLM error.
     */
    data class ExternalServiceError(
        val llmError: LLMCompletionError
    ) : ConversationTurnEvent

    /**
     * Signals that the turn has finished emitting events.
     */
    data object TurnCompleted : ConversationTurnEvent
}