package eu.torvian.chatbot.app.service.apiclient.ktor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
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
 * @property client The Ktor HttpClient instance configured to talk to the backend.
 *                  This client instance itself can be multiplatform, while its engine
 *                  will be platform-specific (e.g., CIO for JVM).
 */
abstract class BaseApiClient(protected val client: HttpClient) {

    companion object {
        private val logger: Logger = LogManager.getLogger(BaseApiClient::class.java)
    }

    /**
      * Executes a suspend block (typically a Ktor HttpClient call) and wraps the result
      * in an [arrow.core.Either] of [eu.torvian.chatbot.common.api.ApiError] or [T].
      *
      * Handles expected API errors ([io.ktor.client.plugins.ResponseException] with an [eu.torvian.chatbot.common.api.ApiError] body) by
      * returning [arrow.core.Either.Left] with the deserialized [eu.torvian.chatbot.common.api.ApiError].
      *
      * Handles other unexpected exceptions (network errors, serialization errors, etc.)
      * by mapping them to a generic [eu.torvian.chatbot.common.api.ApiError] and returning [arrow.core.Either.Left].
      *
      * Returns [arrow.core.Either.Right] with the successful result [T] if the block completes without exceptions.
      *
      * @param T The expected type of the successful result.
      * @param block The suspend function block containing the Ktor HttpClient call.
      * @return An [arrow.core.Either] containing an [eu.torvian.chatbot.common.api.ApiError] on the left or the successful result [T] on the right.
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
            // Handles 4xx responses (e.g., 400, 404, 409)
            logger.warn("ClientRequestException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}") // Log for debug
            // Attempt to deserialize ApiError body and return Left. This assumes 4xx errors
            // from our backend will always contain an ApiError DTO.
            e.response.body<ApiError>().left()
        } catch (e: ServerResponseException) {
            // Handles 5xx responses (e.g., 500, 503)
            logger.warn("ServerResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}") // Log for debug
            // Attempt to deserialize ApiError body and return Left. This assumes 5xx errors
            // from our backend will always contain an ApiError DTO.
            e.response.body<ApiError>().left()
        } catch (e: RedirectResponseException) {
            // Handles 3xx responses - generally not expected for our internal API
            logger.warn("RedirectResponseException: Status ${e.response.status}")
            // Attempt to deserialize ApiError body, though 3xx responses typically don't have one.
            // This might lead to SerializationException if body() fails to parse, which is caught below.
            e.response.body<ApiError>().left()
        } catch (e: ResponseException) {
            // Catch-all for other Ktor ResponseException types not covered above (less common, e.g., UnresolvedAddressException)
            logger.warn("Unexpected ResponseException: Status ${e.response.status}, Body: ${e.response.bodyAsText()}")
            // Fallback: Map to a generic error if ApiError body couldn't be read or wasn't present
            mapToGenericApiError(e, "HTTP Response Error")
        } catch (e: SerializationException) {
            // Handles errors during serialization (request body) or deserialization (response body)
            logger.warn("SerializationException: ${e.message}") // Log for debug
            // Map to a generic internal error indicating a data contract mismatch or corrupted data
            mapToGenericApiError(e, "Data Serialization/Deserialization Error")
        } catch (e: CancellationException) {
            // Coroutine cancellation - important to rethrow it to propagate cancellation properly
            throw e
        } catch (e: Exception) {
            // Handles all other exceptions (e.g., network errors like connection refused, timeouts,
            // unexpected runtime errors not caught by specific Ktor exceptions)
            logger.error("Unexpected Exception during API call: ${e.message}", e) // Log for debug
            // e.printStackTrace() // Print stack trace for unexpected errors for detailed debugging
            // Map to a generic internal error, potentially indicating external service issue if it's a transport error
            mapToGenericApiError(e, "Network or Unexpected Client Error")
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