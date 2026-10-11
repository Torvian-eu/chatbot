package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryError
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the auxiliary compaction call opts out of the provider-request capture, so a summary
 * generation can never surface in the request viewer as if it were a chat answer.
 */
class AuxiliaryCompactionSummarizerRawRequestTest {

    private val provider = LLMProvider(
        id = 1L,
        apiKeyId = "key",
        name = "OpenAI",
        description = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val model = LLMModel(
        id = 1L,
        name = "gpt-4o-mini",
        providerId = provider.id,
        active = true,
        displayName = "GPT-4o mini"
    )

    private val settings = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = model.id,
        temperature = 0.2f,
        maxTokens = 500,
        customParams = null
    )

    @Test
    fun `the auxiliary summary call supplies no request observer`() = runTest {
        val capturedObservers = mutableListOf<(suspend (RawLlmRequestPayload) -> Unit)?>()
        val client = object : LLMApiClient {
            override suspend fun completeChat(
                messages: List<RawChatMessage>,
                modelConfig: LLMModel,
                provider: LLMProvider,
                settings: ModelSettings,
                apiKey: String?,
                tools: List<ToolDefinition>?,
                systemMessage: String?,
                onRequestPrepared: (suspend (RawLlmRequestPayload) -> Unit)?
            ): Either<LLMCompletionError, LLMCompletionResult> {
                capturedObservers.add(onRequestPrepared)
                return LLMCompletionResult(
                    id = "summary",
                    choices = listOf(
                        LLMCompletionResult.CompletionChoice(
                            role = "assistant",
                            content = "A summary.",
                            finishReason = "stop",
                            index = 0
                        )
                    ),
                    usage = UsageStats(1, 1, 2),
                    metadata = emptyMap()
                ).right()
            }

            override suspend fun completeChatStreaming(
                messages: List<RawChatMessage>,
                modelConfig: LLMModel,
                provider: LLMProvider,
                settings: ModelSettings,
                apiKey: String?,
                tools: List<ToolDefinition>?,
                systemMessage: String?,
                onRequestPrepared: (suspend (RawLlmRequestPayload) -> Unit)?
            ) = error("The auxiliary summarizer never streams")

            override suspend fun discoverModels(
                provider: LLMProvider,
                apiKey: String?
            ): Either<ModelDiscoveryError, ModelDiscoveryResult> = error("Not used")
        }

        val summarizer = AuxiliaryCompactionSummarizer(client)
        val result = summarizer.summarize(
            config = LLMConfig(provider, model, settings, "api-key"),
            messages = listOf(RawChatMessage.User("Hello")),
            instruction = "Summarize the conversation."
        )

        assertTrue(result.isRight())
        assertNull(capturedObservers.single(), "The auxiliary call must not request a capture")
    }
}
