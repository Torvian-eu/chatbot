package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.serialization.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Abstract base class for frontend API clients.
 *
 * Provides a protected helper function [safeApiCall] to execute Ktor HttpClient requests
 * and wrap the results in an [Either<ApiError, T>]. This centralizes error handling
 * for the internal API communication.
 *
 * Specific API clients (e.g., KtorChatApiClient, KtorSessionApiClient) should
 * inherit from this class and use the [safeApiCall] helper for all their network operations.
 *
 * @property client The Ktor HttpClient instance to use for making API calls.
 */
abstract class BaseApiClient(protected val client: HttpClient) {

    companion object {
        private val logger: KmpLogger = kmpLogger<BaseApiClient>()
    }

    /**
     * Executes a suspend block (typically a Ktor HttpClient call) and wraps the result
     * in an [Either] of [ApiError] or [T].
     *
     * Handles expected API errors ([ResponseException] with an [ApiError] body) by
     * returning [Either.Left] with the deserialized [ApiError].
     *
     * Handles other unexpected exceptions (network errors, serialization errors, etc.)
     * by mapping them to a generic [ApiError] and returning [Either.Left].
     *
     * Returns [Either.Right] with the successful result [T] if the block completes without exceptions.
     *
     * @param T The expected type of the successful result.
     * @param block The suspend function block containing the Ktor HttpClient call.
     * @return An [Either] containing an [ApiError] on the left or the successful result [T] on the right.
     */
    protected suspend fun <T> safeApiCall(
        block: suspend () -> T
    ): Either<ApiError, T> {
        return try {
            // Execute the actual API call block
            val result = block()
            // If successful, wrap in Either.Right
            result.right()
        } catch (e: ClientRequestException) {
            logger.warn("ClientRequestException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
            tryDeserializeApiError(e.response, e) // Use helper for error body deserialization
        } catch (e: ServerResponseException) {
            logger.warn("ServerResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
            tryDeserializeApiError(e.response, e) // Use helper for error body deserialization
        } catch (e: RedirectResponseException) {
            // Handles 3xx responses - less common with expectSuccess = true, but possible
            logger.warn("RedirectResponseException: Status ${e.response.status}")
            tryDeserializeApiError(e.response, e) // Use helper for error body deserialization
        } catch (e: ResponseException) {
            // Catch-all for other Ktor ResponseException types or deserialization failures of error body
            logger.warn("Unexpected ResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
            mapToGenericApiError(e, "HTTP Response Error")
        } catch (e: ContentConvertException) {
            // Handles errors during serialization (request body) or deserialization (response body)
            logger.warn("ContentConvertException: ${e.message}")
            mapToGenericApiError(e, "Data Serialization/Deserialization Error")
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions
        } catch (e: Exception) {
            logger.error("Unexpected Exception during API call: ${e.message}", e)
            mapToGenericApiError(e, "Network or Unexpected Client Error")
        }
    }

    /**
     * Helper to attempt deserializing an ApiError from a ResponseException's body.
     * If deserialization fails, falls back to a generic error based on the original exception.
     */
    private suspend fun <T> tryDeserializeApiError(
        response: HttpResponse,
        originalException: ResponseException
    ): Either<ApiError, T> {
        return try {
            // Attempt to read the body as ApiError
            response.body<ApiError>().left()
        } catch (se: SerializationException) {
            // If reading the body as ApiError fails, map the original exception
            logger.warn("SerializationException reading error body for status ${response.status.value}: ${se.message}")
            mapToGenericApiError(originalException, "HTTP Response Error (Malformed Error Body)")
        } catch (e: Exception) {
            // Catch any other exception during body reading (e.g., I/O error)
            logger.warn("Unexpected exception reading error body for status ${response.status.value}: ${e.message}")
            mapToGenericApiError(originalException, "HTTP Response Error (Failed to read Error Body)")
        }
    }

    /**
     * Helper function to map various exceptions to a generic ApiError.
     *
     * @param e The original throwable.
     * @param contextMessage A brief description of the context where the error occurred.
     * @return An [Either.Left] containing a generic [ApiError].
     */
    private fun <T> mapToGenericApiError(e: Throwable, contextMessage: String): Either<ApiError, T> {
        // Attempt to determine a sensible status code, otherwise default to 500 for internal errors
        val statusCode = if (e is ResponseException) e.response.status.value else 500
        // Create a generic ApiError using CommonApiErrorCodes.UNAVAILABLE for network issues
        // or CommonApiErrorCodes.INTERNAL for other unexpected client-side errors.
        val errorCode =
            if (e is IOException) CommonApiErrorCodes.UNAVAILABLE.code else CommonApiErrorCodes.INTERNAL.code
        val apiError = ApiError(
            statusCode = statusCode,
            code = errorCode,
            message = "$contextMessage: ${e.message}",
            details = mapOf("originalExceptionType" to e::class.simpleName.orEmpty())
        )
        return apiError.left()
    }
}