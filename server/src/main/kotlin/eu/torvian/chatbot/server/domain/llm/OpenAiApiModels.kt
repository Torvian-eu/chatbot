package eu.torvian.chatbot.server.domain.llm

import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for OpenAI-compatible API responses.
 * These models represent the structure of responses from external LLM APIs.
 */
object OpenAiApiModels {

    /**
     * Represents a chat completion response from an OpenAI-compatible API.
     */
    @Serializable
    data class ChatCompletionResponse(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<Choice>,
        val usage: Usage
    ) {
        /**
         * Represents a single choice in the chat completion response.
         */
        @Serializable
        data class Choice(
            val index: Int,
            val message: Message,
            val finish_reason: String
        ) {
            /**
             * Represents a message within a choice.
             */
            @Serializable
            data class Message(
                val role: String,
                val content: String
            )
        }

        /**
         * Represents token usage information for the completion.
         */
        @Serializable
        data class Usage(
            val prompt_tokens: Int,
            val completion_tokens: Int,
            val total_tokens: Int
        )
    }

    /**
     * Represents a chat completion request to an OpenAI-compatible API.
     */
    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<RequestMessage>,
        val temperature: Float? = null,
        val max_tokens: Int? = null,
        val top_p: Float? = null,
        val frequency_penalty: Float? = null,
        val presence_penalty: Float? = null,
        val stop: List<String>? = null
    ) {
        /**
         * Represents a message in the chat completion request.
         */
        @Serializable
        data class RequestMessage(
            val role: String,
            val content: String
        )
    }
}