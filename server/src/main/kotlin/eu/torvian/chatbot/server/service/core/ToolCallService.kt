package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Service interface for managing tool calls.
 * Contains core business logic related to tool calls, independent of API or data access details.
 */
interface ToolCallService {
    /**
     * Retrieves all tool calls for a specific session.
     * Used for building context when calling the LLM and for audit trails.
     *
     * @param sessionId The ID of the session
     * @return List of tool calls for all messages in the session
     */
    suspend fun getToolCallsBySessionId(sessionId: Long): List<ToolCall>
}

