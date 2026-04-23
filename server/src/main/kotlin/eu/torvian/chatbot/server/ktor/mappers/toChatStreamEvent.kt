package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent.*
import eu.torvian.chatbot.server.service.core.MessageStreamEvent

/**
 * Converts internal MessageStreamEvent to external ChatStreamEvent for API response.
 */
fun MessageStreamEvent.toChatStreamEvent(): ChatStreamEvent {
    return when (this) {
        is MessageStreamEvent.UserMessageSaved -> UserMessageSaved(
            userMessage = userMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageStreamEvent.AssistantMessageStarted -> AssistantMessageStart(
            assistantMessage = assistantMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageStreamEvent.AssistantMessageDelta -> AssistantMessageDelta(
            messageId = messageId,
            deltaContent = deltaContent
        )

        is MessageStreamEvent.AssistantMessageFinished -> AssistantMessageEnd(
            assistantMessage = assistantMessage
        )

        is MessageStreamEvent.ToolCallDelta -> ToolCallDelta(
            messageId = messageId,
            index = index,
            id = id,
            name = name,
            argumentsDelta = argumentsDelta
        )

        is MessageStreamEvent.ToolCallsReceived -> ToolCallsReceived(
            toolCalls = toolCalls
        )

        is MessageStreamEvent.ToolExecutionCompleted -> ToolExecutionCompleted(
            toolCall = toolCall
        )

        is MessageStreamEvent.ToolCallApprovalRequested -> ToolCallApprovalRequested(
            toolCall = toolCall
        )

        is MessageStreamEvent.ToolCallExecuting -> ToolCallExecuting(
            toolCall = toolCall
        )

        is MessageStreamEvent.StreamCompleted -> StreamCompleted
    }
}