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
 * Model discovery strategy for Ollama providers.
 */
class OllamaModelDiscoveryStrategy(private val json: Json) : ModelDiscoveryStrategy {
    private val logger: Logger = LogManager.getLogger(OllamaModelDiscoveryStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OLLAMA

    override fun prepareRequest(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError.ConfigurationError, ApiRequestConfig> {
        val headers = buildMap {
            if (apiKey != null) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }

        return ApiRequestConfig(
            path = "/api/tags",
            method = GenericHttpMethod.GET,
            body = "",
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = headers
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<ModelDiscoveryError.InvalidResponseError, ModelDiscoveryResult> {
        return try {
            val payload = json.decodeFromString<OllamaModelListResponse>(responseBody)
            ModelDiscoveryResult(
                models = payload.models.map { model ->
                    ModelDiscoveryResult.DiscoveredModel(
                        id = model.model,
                        displayName = model.name,
                        metadata = mapOf(
                            "digest" to model.digest,
                            "size" to model.size.toString(),
                            "modified_at" to model.modifiedAt
                        )
                    )
                }
            ).right()
        } catch (e: Exception) {
            logger.error("Failed to parse Ollama model discovery response", e)
            ModelDiscoveryError.InvalidResponseError(
                "Failed to parse Ollama model discovery response: ${e.message}",
                e
            ).left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): ModelDiscoveryError {
        val parsedMessage = try {
            json.decodeFromString<OllamaErrorResponse>(errorBody).error
        } catch (_: Exception) {
            errorBody.take(200)
        }

        return when (statusCode) {
            401, 403 -> ModelDiscoveryError.AuthenticationError(
                "Ollama API authentication failed: $parsedMessage"
            )

            else -> ModelDiscoveryError.ApiError(
                statusCode = statusCode,
                message = "Ollama API returned error $statusCode: $parsedMessage",
                errorBody = errorBody
            )
        }
    }

    /**
     * Ollama model listing response payload.
     *
     * @property models List of locally available models.
     */
    @Serializable
    private data class OllamaModelListResponse(
        val models: List<OllamaModel>
    )

    /**
     * Ollama model entry in a tags/list response.
     *
     * @property name Human-readable model name.
     * @property model Canonical model identifier used for API requests.
     * @property modifiedAt Last modification timestamp in ISO-8601 format.
     * @property size Model artifact size in bytes.
     * @property digest Content digest for the model artifact.
     */
    @Serializable
    private data class OllamaModel(
        val name: String,
        val model: String,
        @SerialName("modified_at")
        val modifiedAt: String,
        val size: Long,
        val digest: String
    )

    /**
     * Ollama error payload wrapper.
     *
     * @property error Provider error message.
     */
    @Serializable
    private data class OllamaErrorResponse(
        val error: String
    )
}


