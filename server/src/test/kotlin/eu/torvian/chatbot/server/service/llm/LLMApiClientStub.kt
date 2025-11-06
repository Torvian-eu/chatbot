package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Stubbed implementation of [LLMApiClient] for testing purposes.
 *
 * This class provides a canned response without making an actual network call.
 * It's used in tests to isolate the system under test from external dependencies.
 */
class LLMApiClientStub : LLMApiClient {
    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Either<LLMCompletionError, LLMCompletionResult> {
        return LLMCompletionResult(
            id = "test-completion-id",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = "This is a test response from the stubbed LLM API client for model '${modelConfig.name}'.",
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(
                promptTokens = messages.sumOf { (it.content ?: "").length / 4 + 1 } + 10,
                completionTokens = 50,
                totalTokens = messages.sumOf { (it.content ?: "").length / 4 + 1 } + 60
            ),
            metadata = mapOf(
                "api_object" to "chat.completion",
                "api_created" to 1234567890L,
                "api_model" to modelConfig.name
            )
        ).right()
    }

    override fun completeChatStreaming(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Flow<Either<LLMCompletionError, LLMStreamChunk>> {
        TODO("Not yet implemented")
    }
}
