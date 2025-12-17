package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for tool call records.
 *
 * Each row represents a single invocation of a tool during a conversation.
 * Tool calls are always linked to an assistant message (the message that triggered the tool call).
 * Multiple tool calls can be associated with a single assistant message when the LLM decides
 * to use multiple tools in one response turn.
 *
 * The tool calling flow:
 * 1. User sends a message
 * 2. LLM responds with tool call request(s)
 * 3. Tool(s) are executed and results saved here
 * 4. Results are sent back to LLM
 * 5. LLM generates final response
 *
 * @property messageId Reference to the assistant message that this tool call belongs to.
 * @property toolDefinitionId Optional reference to the tool definition that was used.
 *                            This can be null if the LLM hallucinates a tool name.
 * @property toolName Name of the tool. This is directly stored and is always present,
 *                      even if it's a hallucinated name from the LLM.
 * @property toolCallId Optional unique identifier from the LLM provider (OpenAI provides, Ollama doesn't)
 * @property inputJson JSON object containing the arguments passed to the tool
 * @property outputJson JSON object containing the results returned by the tool
 * @property status Current status of the tool call execution
 * @property errorMessage Error details if the tool execution failed
 * @property executedAt Unix timestamp (milliseconds) when the tool was executed
 * @property durationMs Execution time in milliseconds (null if still pending)
 */
object ToolCallTable : LongIdTable("tool_calls") {
    val messageId = reference("message_id", ChatMessageTable, onDelete = ReferenceOption.CASCADE)
    val toolDefinitionId = optReference("tool_definition_id", ToolDefinitionTable, onDelete = ReferenceOption.CASCADE)
    val toolName = varchar("tool_name", 255)
    val toolCallId = text("tool_call_id").nullable()
    val inputJson = text("input_json").nullable()
    val outputJson = text("output_json").nullable()
    val status = enumerationByName<ToolCallStatus>("status", 50)
    val errorMessage = text("error_message").nullable()
    val denialReason = text("denial_reason").nullable()
    val executedAt = long("executed_at")
    val durationMs = long("duration_ms").nullable()

    init {
        index(false, messageId)
    }
}
