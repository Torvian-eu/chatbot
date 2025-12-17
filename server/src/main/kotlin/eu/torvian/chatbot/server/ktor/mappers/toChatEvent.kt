package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.server.service.core.MessageEvent

fun MessageEvent.toChatEvent(): ChatEvent {
    return when (this) {
        is MessageEvent.UserMessageSaved -> ChatEvent.UserMessageSaved(
            userMessage = userMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageEvent.AssistantMessageSaved -> ChatEvent.AssistantMessageSaved(
            assistantMessage = assistantMessage,
            updatedParentMessage = updatedParentMessage
        )

        is MessageEvent.ToolCallsReceived -> ChatEvent.ToolCallsReceived(
            toolCalls = toolCalls
        )

        is MessageEvent.ToolExecutionCompleted -> ChatEvent.ToolExecutionCompleted(
            toolCall = toolCall
        )

        is MessageEvent.StreamCompleted -> ChatEvent.StreamCompleted

        is MessageEvent.LocalMCPToolCallReceived -> ChatEvent.LocalMCPToolCallReceived(
            request = request
        )

        is MessageEvent.ToolCallApprovalRequested -> ChatEvent.ToolCallApprovalRequested(
            toolCall = toolCall
        )
    }
}