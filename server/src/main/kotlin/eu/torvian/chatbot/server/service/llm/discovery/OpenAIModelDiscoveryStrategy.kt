package eu.torvian.chatbot.server.service.llm.discovery

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.ApiRequestConfig
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryError
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryResult
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryStrategy
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Model discovery strategy for OpenAI-compatible providers.
 */
class OpenAIModelDiscoveryStrategy(private val json: Json) : ModelDiscoveryStrategy {
    private val logger: Logger = LogManager.getLogger(OpenAIModelDiscoveryStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OPENAI

    override fun prepareRequest(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError.ConfigurationError, ApiRequestConfig> {
        if (provider.apiKeyId != null && apiKey == null) {
            return ModelDiscoveryError.ConfigurationError(
                "OpenAI provider '${provider.name}' requires an API key, but none was provided."
            ).left()
        }

        val headers = buildMap {
            if (apiKey != null) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }

        return ApiRequestConfig(
            path = "/models",
            method = GenericHttpMethod.GET,
            body = "",
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = headers
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<ModelDiscoveryError.InvalidResponseError, ModelDiscoveryResult> {
        return try {
            val payload = json.decodeFromString<OpenAiModelListResponse>(responseBody)
            ModelDiscoveryResult(
                models = payload.data.map { model ->
                    ModelDiscoveryResult.DiscoveredModel(
                        id = model.id,
                        displayName = model.id,
                        metadata = mapOf(
                            "object" to model.objectType,
                            "created" to model.created.toString(),
                            "owned_by" to model.ownedBy
                        )
                    )
                }
            ).right()
        } catch (e: Exception) {
            logger.error("Failed to parse OpenAI model discovery response", e)
            ModelDiscoveryError.InvalidResponseError(
                "Failed to parse OpenAI model discovery response: ${e.message}",
                e
            ).left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): ModelDiscoveryError {
        val parsedMessage = try {
            json.decodeFromString<OpenAiErrorResponse>(errorBody).error.message
        } catch (_: Exception) {
            errorBody.take(200)
        }

        return when (statusCode) {
            401, 403 -> ModelDiscoveryError.AuthenticationError(
                "OpenAI API authentication failed: $parsedMessage"
            )

            else -> ModelDiscoveryError.ApiError(
                statusCode = statusCode,
                message = "OpenAI API returned error $statusCode: $parsedMessage",
                errorBody = errorBody
            )
        }
    }

    /**
     * OpenAI model listing response payload.
     *
     * @property data List of models returned by the provider.
     */
    @Serializable
    private data class OpenAiModelListResponse(
        val data: List<OpenAiModel>
    )

    /**
     * OpenAI model entry in a model listing response.
     *
     * @property id Unique model identifier.
     * @property objectType API object type (typically "model").
     * @property created Unix timestamp when the model metadata was created.
     * @property ownedBy Provider owner namespace for the model.
     */
    @Serializable
    private data class OpenAiModel(
        val id: String,
        @SerialName("object")
        val objectType: String,
        val created: Long,
        @SerialName("owned_by")
        val ownedBy: String
    )

    /**
     * OpenAI error response wrapper.
     *
     * @property error Structured error details.
     */
    @Serializable
    private data class OpenAiErrorResponse(
        val error: OpenAiErrorDetail
    ) {
        /**
         * OpenAI error details payload.
         *
         * @property message Provider error message.
         */
        @Serializable
        data class OpenAiErrorDetail(
            val message: String
        )
    }
}

