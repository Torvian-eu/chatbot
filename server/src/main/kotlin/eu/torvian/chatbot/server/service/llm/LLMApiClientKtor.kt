package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.domain.llm.OpenAiApiModels.ChatCompletionResponse
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.UUID

/**
 * Ktor implementation of the [LLMApiClient] for OpenAI-compatible endpoints.
 *
 * NOTE: For Sprint 1, this implementation is STUBBED to return a canned response
 * without making an actual network call.
 */
class LLMApiClientKtor(private val httpClient: HttpClient) : LLMApiClient {

    companion object {
        private val logger: Logger = LogManager.getLogger(LLMApiClientKtor::class.java)
    }

    override suspend fun completeChat(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?
    ): Either<String, ChatCompletionResponse> {
        logger.info("LLMApiClientKtor (STUBBED): Received request for model ${modelConfig.name} from provider ${provider.name} (${provider.baseUrl}) with settings ${settings.name}")
        logger.info("LLMApiClientKtor (STUBBED): Context messages received: ${messages.size}")
        messages.forEachIndexed { index, msg ->
            logger.debug("LLMApiClientKtor (STUBBED) Context[$index]: ${msg.role}: ${msg.content.take(50)}...")
        }

        // Simulate network latency
        delay(800)

        val fakeContent = "This is a stubbed response for model '${modelConfig.name}' " +
                          "replying to: \"${messages.lastOrNull()?.content?.take(40)}...\""

        val choice = ChatCompletionResponse.Choice(
            index = 0,
            message = ChatCompletionResponse.Choice.Message("assistant", fakeContent),
            finish_reason = "stop"
        )

        val usage = ChatCompletionResponse.Usage(
            prompt_tokens = messages.sumOf { it.content.length / 4 + 1 } + 10, // Rough token estimate + overhead
            completion_tokens = fakeContent.length / 4 + 10,
            total_tokens = (messages.sumOf { it.content.length / 4 + 1 } + 10) + (fakeContent.length / 4 + 10)
        )

        val response = ChatCompletionResponse(
            id = "stubbed-chatcmpl-${UUID.randomUUID()}",
            `object` = "chat.completion",
            created = Clock.System.now().epochSeconds,
            model = modelConfig.name,
            choices = listOf(choice),
            usage = usage
        )

        logger.info("LLMApiClientKtor (STUBBED): Returning fake response.")
        return response.right()

        // --- REAL IMPLEMENTATION FOR SPRINT 2+ (Part of E1.S4 L) ---
        /*
        val endpoint = "${modelConfig.baseUrl}/v1/chat/completions" // Assuming OpenAI compatible path
        val requestBody = ChatCompletionRequest( // Assuming this DTO exists in external.models
            model = modelConfig.name, // Need to map modelConfig.name to actual model string if different from config name
            messages = messages.map { it.toApiMessage() }, // Need mapping function ChatMessage -> OpenAiApiModels.Message
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            // ... map other settings parameters ...
            // customParamsJson also needs parsing and mapping if needed by the API
        )

        try {
            val response: ChatCompletionResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                setBody(requestBody)
            }.body()

            return response
        } catch (e: Exception) {
            logger.error("LLMApiClientKtor: Error calling LLM API", e)
            throw e // Let the service layer handle this
        }
        */
    }
}
