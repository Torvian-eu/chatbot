package eu.torvian.chatbot.server.service.llm

import kotlinx.serialization.Serializable

/**
 * Raw message types for LLM API communication.
 *
 * These are simplified versions of [eu.torvian.chatbot.common.models.core.ChatMessage] without threading information.
 * They are used to build context for LLM requests and contain only the information
 * needed by the LLM provider APIs (OpenAI, Ollama, etc.).
 *
 * Key differences from [eu.torvian.chatbot.common.models.core.ChatMessage]:
 * - No message IDs or database references
 * - No parent/child relationships or threading
 * - No metadata like creation timestamps
 * - Focused on role, content, and tool-related information
 *
 * The conversion from [eu.torvian.chatbot.common.models.core.ChatMessage] to [RawChatMessage] happens in the service layer
 * when building context for LLM requests.
 */
sealed class RawChatMessage {
    /**
     * The role of this message in the conversation.
     * Must be one of: "user", "assistant", or "tool".
     */
    abstract val role: String

    /**
     * The text content of the message.
     * May be null for assistant messages that only contain tool calls,
     * or for messages where content is not applicable.
     */
    abstract val content: String?

    /**
     * User message for LLM API.
     *
     * Represents a message from the user in the conversation.
     * Always has non-null content.
     *
     * @property content The user's message text
     */
    @Serializable
    data class User(
        override val content: String
    ) : RawChatMessage() {
        override val role: String = "user"
    }

    /**
     * Assistant message for LLM API.
     *
     * Represents a message from the AI assistant in the conversation.
     * Can include tool calls made by the assistant. When tool calls are present,
     * content may be null or empty.
     *
     * @property content The assistant's response text (null if only tool calls)
     * @property toolCalls List of tool calls made by the assistant (null if none)
     */
    @Serializable
    data class Assistant(
        override val content: String?,
        val toolCalls: List<ToolCall>? = null
    ) : RawChatMessage() {
        override val role: String = "assistant"

        /**
         * Represents a tool call request from the LLM.
         *
         * This is a simplified representation used in the message context.
         * The actual tool call execution and results are tracked separately
         * in the database via [eu.torvian.chatbot.common.models.tool.ToolCall].
         *
         * Note: The arguments are kept as a JSON string rather than parsed JsonObject
         * because LLM providers (especially OpenAI) may return invalid JSON or include
         * hallucinated fields. The string format preserves the original response and
         * allows for validation and error handling in the execution layer.
         *
         * @property id Unique identifier for this tool call from the LLM provider.
         *              Null for providers that don't provide IDs (e.g., Ollama).
         * @property name Name of the tool/function to call
         * @property arguments Input parameters as a JSON string. Null for functions that
         *                     don't take any parameters. May not be valid JSON and may
         *                     contain hallucinated fields from the LLM.
         */
        @Serializable
        data class ToolCall(
            val id: String? = null,
            val name: String,
            val arguments: String? = null
        )
    }

    /**
     * Tool result message for LLM API.
     *
     * Represents the output from a tool execution, sent back to the LLM
     * so it can incorporate the results into its response. These messages
     * are not stored as separate [eu.torvian.chatbot.common.models.core.ChatMessage] records in the database;
     * instead, they are reconstructed from [eu.torvian.chatbot.common.models.tool.ToolCall]
     * records when building context.
     *
     * @property content The tool output as a JSON string
     * @property toolCallId ID linking this result to the original tool call
     * @property name Name of the tool that was executed
     */
    @Serializable
    data class Tool(
        override val content: String,
        val toolCallId: String,
        val name: String
    ) : RawChatMessage() {
        override val role: String = "tool"
    }
}