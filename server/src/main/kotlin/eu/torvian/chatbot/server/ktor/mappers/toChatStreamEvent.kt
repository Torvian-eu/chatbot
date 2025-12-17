package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent

/**
 * Converts internal MessageStreamEvent to external ChatStreamEvent for API response.
 */
fun MessageStreamEvent.toChatStreamEvent(): ChatStreamEvent {
    return when (this) {
        is MessageStreamEvent.UserMessageSaved -> ChatStreamEvent.UserMessageSaved(
            userMessage = userMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageStreamEvent.AssistantMessageStarted -> ChatStreamEvent.AssistantMessageStart(
            assistantMessage = assistantMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageStreamEvent.AssistantMessageDelta -> ChatStreamEvent.AssistantMessageDelta(
            messageId = messageId,
            deltaContent = deltaContent
        )

        is MessageStreamEvent.AssistantMessageFinished -> ChatStreamEvent.AssistantMessageEnd(
            assistantMessage = assistantMessage
        )

        is MessageStreamEvent.ToolCallDelta -> ChatStreamEvent.ToolCallDelta(
            messageId = messageId,
            index = index,
            id = id,
            name = name,
            argumentsDelta = argumentsDelta
        )

        is MessageStreamEvent.ToolCallsReceived -> ChatStreamEvent.ToolCallsReceived(
            toolCalls = toolCalls
        )

        is MessageStreamEvent.ToolExecutionCompleted -> ChatStreamEvent.ToolExecutionCompleted(
            toolCall = toolCall
        )

        is MessageStreamEvent.LocalMCPToolCallReceived -> ChatStreamEvent.LocalMCPToolCallReceived(
            request = request
        )

        is MessageStreamEvent.ToolCallApprovalRequested -> ChatStreamEvent.ToolCallApprovalRequested(
            toolCall = toolCall
        )

        is MessageStreamEvent.StreamCompleted -> ChatStreamEvent.StreamCompleted
    }
}