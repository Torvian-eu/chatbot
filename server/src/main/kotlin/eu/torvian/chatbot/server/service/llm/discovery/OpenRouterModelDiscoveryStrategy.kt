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
 * Model discovery strategy for OpenRouter providers.
 */
class OpenRouterModelDiscoveryStrategy(private val json: Json) : ModelDiscoveryStrategy {
    private val logger: Logger = LogManager.getLogger(OpenRouterModelDiscoveryStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OPENROUTER

    override fun prepareRequest(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError.ConfigurationError, ApiRequestConfig> {
        if (apiKey.isNullOrBlank()) {
            return ModelDiscoveryError.ConfigurationError(
                "OpenRouter provider '${provider.name}' requires an API key, but none was provided."
            ).left()
        }

        return ApiRequestConfig(
            path = "/models",
            method = GenericHttpMethod.GET,
            body = "",
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $apiKey")
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<ModelDiscoveryError.InvalidResponseError, ModelDiscoveryResult> {
        return try {
            val payload = json.decodeFromString<OpenRouterModelsResponse>(responseBody)
            ModelDiscoveryResult(
                models = payload.data.map { model ->
                    ModelDiscoveryResult.DiscoveredModel(
                        id = model.id,
                        displayName = model.name ?: model.id,
                        metadata = buildMap {
                            model.canonicalSlug?.let { put("canonical_slug", it) }
                            model.created?.let { put("created", it.toString()) }
                            model.description?.let { put("description", it) }
                            model.contextLength?.let { put("context_length", it.toString()) }
                        }
                    )
                }
            ).right()
        } catch (e: Exception) {
            logger.error("Failed to parse OpenRouter model discovery response", e)
            ModelDiscoveryError.InvalidResponseError(
                "Failed to parse OpenRouter model discovery response: ${e.message}",
                e
            ).left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): ModelDiscoveryError {
        val parsedMessage = try {
            val error = json.decodeFromString<OpenRouterErrorResponse>(errorBody).error
            error.message
        } catch (_: Exception) {
            errorBody.take(200)
        }

        return when (statusCode) {
            401, 403 -> ModelDiscoveryError.AuthenticationError(
                "OpenRouter API authentication failed: $parsedMessage"
            )

            else -> ModelDiscoveryError.ApiError(
                statusCode = statusCode,
                message = "OpenRouter API returned error $statusCode: $parsedMessage",
                errorBody = errorBody
            )
        }
    }

    /**
     * OpenRouter model listing response payload.
     *
     * @property data List of models returned by the provider.
     */
    @Serializable
    private data class OpenRouterModelsResponse(
        val data: List<OpenRouterModel>
    )

    /**
     * OpenRouter model entry in the models response.
     *
     * @property id Unique model identifier.
     * @property canonicalSlug Canonical slug for the model when present.
     * @property name Human-friendly model name.
     * @property created Unix timestamp when model metadata was created.
     * @property description Optional model description.
     * @property contextLength Optional maximum context length.
     */
    @Serializable
    private data class OpenRouterModel(
        val id: String,
        @SerialName("canonical_slug")
        val canonicalSlug: String? = null,
        val name: String? = null,
        val created: Long? = null,
        val description: String? = null,
        @SerialName("context_length")
        val contextLength: Long? = null
    )

    /**
     * OpenRouter error response wrapper.
     *
     * @property error Structured error details.
     */
    @Serializable
    private data class OpenRouterErrorResponse(
        val error: OpenRouterErrorDetail
    ) {
        /**
         * OpenRouter error detail payload.
         *
         * @property code Provider error code.
         * @property message Human-readable provider error message.
         */
        @Serializable
        data class OpenRouterErrorDetail(
            val code: Int? = null,
            val message: String
        )
    }
}

