package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
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
     * Signals that one operator tool execution must be relayed to the operator.
     *
     * This is a generic envelope: [payload] is the JSON-serialized, tool-specific execution request
     * (e.g. [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest] for `spawn_agent`) and
     * [toolName] tells the operator which deserializer to use. The operator echoes [toolCallId] back
     * in `ChatClientEvent.ToolExecutionResult` to correlate the reply.
     *
     * @property toolCallId Persisted tool-call identifier (correlation key).
     * @property toolName Operator-tool name (e.g. `spawn_agent`); unique per user because operator
     *            tools are per-user instances, so it doubles as the payload discriminator.
     * @property payload JSON text of the tool-specific payload.
     */
    data class OperatorToolExecutionRequested(
        val toolCallId: Long,
        val toolName: String,
        val payload: String
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
     * Signals that the automated compaction policy aborted the turn before the primary LLM call.
     *
     * The oversized uncompacted primary request is never sent; the turn terminates with this error
     * followed by [TurnCompleted]. Previously persisted user/assistant/tool rows remain durable.
     *
     * @property error Typed compaction failure category.
     */
    data class CompactionFailed(
        val error: ConversationCompactionError
    ) : ConversationTurnEvent

    /**
     * Signals that a conversation-compaction chunk was persisted by the preflight and the turn
     * proceeds to the primary call that uses it.
     *
     * Emission is strictly tied to usage: the orchestrator emits this immediately before the primary
     * assistant step that consumes the chunk, so a chunk persisted by `preparePrimaryContext` is
     * always matched with the response that uses it. No event is emitted on disabled, fit, or
     * hybrid-reuse paths (nothing persisted). The public surfaces derive a bounded, provider-neutral
     * notification from [chunk] (see `toCompactionCompletedPayload`).
     *
     * @property chunk The persisted chunk that will back the upcoming primary response.
     */
    data class CompactionCompleted(
        val chunk: ConversationCompactionChunk
    ) : ConversationTurnEvent

    /**
     * Signals that the turn has finished emitting events.
     */
    data object TurnCompleted : ConversationTurnEvent
}