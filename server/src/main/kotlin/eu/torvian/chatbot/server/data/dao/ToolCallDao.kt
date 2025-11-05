package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.error.DeleteToolCallError
import eu.torvian.chatbot.server.data.dao.error.InsertToolCallError
import eu.torvian.chatbot.server.data.dao.error.ToolCallError
import eu.torvian.chatbot.server.data.dao.error.UpdateToolCallError
import kotlinx.datetime.Instant

/**
 * Data Access Object for ToolCall entities.
 *
 * Provides CRUD operations and queries for tool call execution records during conversations.
 */
interface ToolCallDao {
    /**
     * Retrieves all tool calls associated with a specific assistant message.
     * Multiple tool calls can exist per message when the LLM uses multiple tools.
     *
     * @param messageId The ID of the message
     * @return List of tool calls for the specified message
     */
    suspend fun getToolCallsByMessageId(messageId: Long): List<ToolCall>

    /**
     * Retrieves all tool calls for all messages in a session.
     * Used for building context when calling the LLM.
     *
     * @param sessionId The ID of the session
     * @return List of tool calls for all messages in the session
     */
    suspend fun getToolCallsBySessionId(sessionId: Long): List<ToolCall>

    /**
     * Retrieves a single tool call by ID.
     *
     * @param id The unique identifier of the tool call
     * @return Either [ToolCallError.NotFound] if not found, or the [ToolCall]
     */
    suspend fun getToolCallById(id: Long): Either<ToolCallError.NotFound, ToolCall>

    /**
     * Creates a new tool call record.
     * Use PENDING status initially if execution hasn't completed yet.
     *
     * @param messageId ID of the assistant message this tool call belongs to
     * @param toolDefinitionId Optional ID of the tool definition that was used. Can be null for hallucinated tool calls.
     * @param toolName Name of the tool, can be hallucinated by the LLM. This is directly stored and is always present.
     * @param toolCallId Optional unique ID from the LLM provider
     * @param input JSON string containing the arguments passed to the tool.
     *              Null for parameterless functions.
     *              May contain invalid JSON from the LLM (preserved for error reporting).
     * @param output JSON string containing the results (null if pending)
     * @param status Current execution status
     * @param errorMessage Error details if execution failed
     * @param executedAt Timestamp when the tool was executed
     * @param durationMs Execution time in milliseconds (null if pending)
     * @return Either [InsertToolCallError] or the newly created [ToolCall]
     */
    suspend fun insertToolCall(
        messageId: Long,
        toolDefinitionId: Long?,
        toolName: String,
        toolCallId: String?,
        input: String?,
        output: String?,
        status: ToolCallStatus,
        errorMessage: String?,
        executedAt: Instant,
        durationMs: Long?
    ): Either<InsertToolCallError, ToolCall>

    /**
     * Updates an existing tool call with all fields from the provided entity.
     * This allows setting nullable fields back to null if needed.
     * Typical flow: PENDING → SUCCESS/ERROR
     *
     * @param toolCall The complete tool call entity with updated values
     * @return Either [UpdateToolCallError] or Unit on success
     */
    suspend fun updateToolCall(
        toolCall: ToolCall
    ): Either<UpdateToolCallError, Unit>

    /**
     * Deletes all tool calls for a message.
     * Note: Usually handled automatically via CASCADE delete when message is deleted.
     *
     * @param messageId The ID of the message whose tool calls should be deleted
     * @return Either [DeleteToolCallError] or Unit on success
     */
    suspend fun deleteToolCallsByMessageId(messageId: Long): Either<DeleteToolCallError, Unit>
}
