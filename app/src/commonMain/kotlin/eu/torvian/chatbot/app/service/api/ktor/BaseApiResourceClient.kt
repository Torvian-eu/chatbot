package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.left
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.toApiResourceError
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.serialization.*
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * Abstract base class for API clients that use the new ApiResourceError hierarchy.
 *
 * This class provides comprehensive error handling that translates network and API responses
 * into structured [ApiResourceError] types
 *
 * The [safeApiCall] helper function handles:
 * - Network connectivity issues ([ApiResourceError.NetworkError])
 * - Server-side errors ([ApiResourceError.ServerError] wrapping [ApiError])
 * - Data serialization/parsing problems ([ApiResourceError.SerializationError])
 * - Unexpected system errors ([ApiResourceError.UnknownError])
 *
 * @property client The Ktor HttpClient instance to use for making API calls.
 */
abstract class BaseApiResourceClient(protected val client: HttpClient) {
    
    companion object {
        private val logger: KmpLogger = kmpLogger<BaseApiResourceClient>()
    }

    /**
     * Executes a suspend block (typically a Ktor HttpClient call) and wraps the result
     * in an [Either] of [ApiResourceError] or [T].
     *
     * This method provides comprehensive error handling by categorizing different types
     * of failures into appropriate [ApiResourceError] subtypes:
     *
     * - HTTP response errors are handled by attempting to deserialize [ApiError] from
     *   the response body and wrapping it in [ApiResourceError.ServerError]
     * - Network I/O errors are wrapped in [ApiResourceError.NetworkError]
     * - Serialization/deserialization errors are wrapped in [ApiResourceError.SerializationError]
     * - All other unexpected errors are wrapped in [ApiResourceError.UnknownError]
     *
     * @param T The expected type of the successful result.
     * @param block The suspend function block containing the Ktor HttpClient call.
     * @return An [Either] containing a [ApiResourceError] on the left or the successful result [T] on the right.
     */
    protected suspend fun <T> safeApiCall(
        block: suspend () -> T
    ): Either<ApiResourceError, T> {
        return withContext(ioDispatcher) {
            try {
                Either.Right(block())
            } catch (e: ClientRequestException) {
                // 4xx errors
                logger.warn("ClientRequestException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
                tryDeserializeApiError(e.response)
            } catch (e: ServerResponseException) {
                // 5xx errors
                logger.warn("ServerResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
                tryDeserializeApiError(e.response)
            } catch (e: RedirectResponseException) {
                // 3xx errors
                logger.warn("RedirectResponseException: Status ${e.response.status}")
                tryDeserializeApiError(e.response)
            } catch (e: ResponseException) {
                // Other Ktor ResponseException types
                logger.warn("Unexpected ResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
                ApiResourceError.UnknownError("HTTP Response Error: ${e.message}", e).left()
            } catch (e: IOException) {
                logger.warn("IOException (Network Error): ${e.message}")
                ApiResourceError.NetworkError.from(e).left()
            } catch (e: ContentConvertException) {
                // Serialization/deserialization errors
                logger.warn("ContentConvertException (Serialization Error): ${e.message}")
                ApiResourceError.SerializationError.from(e).left()
            } catch (e: Exception) {
                logger.error("Unexpected Exception during API call: ${e.message}", e)
                ApiResourceError.UnknownError.from(e).left()
            }
        }
    }

    /**
     * Helper to attempt deserializing an [ApiError] from a [ResponseException]'s body
     * and wrap it in a [ApiResourceError.ServerError].
     *
     * If deserialization fails, falls back to appropriate [ApiResourceError] types
     * based on the type of deserialization failure.
     *
     * @param response The HTTP response containing the error body
     * @return An [Either.Left] containing the appropriate [ApiResourceError]
     */
    private suspend fun <T> tryDeserializeApiError(
        response: HttpResponse
    ): Either<ApiResourceError, T> {
        return try {
            val apiError = response.body<ApiError>()
            apiError.toApiResourceError().left()
        } catch (e: ContentConvertException) {
            logger.warn("ContentConvertException reading error body for status ${response.status.value}: ${e.message}")
            ApiResourceError.SerializationError("Failed to parse server error response: ${e.message}", e).left()
        } catch (e: Exception) {
            logger.warn("Unexpected exception reading error body for status ${response.status.value}: ${e.message}")
            ApiResourceError.UnknownError("Failed to process server error response: ${e.message}", e).left()
        }
    }
}
