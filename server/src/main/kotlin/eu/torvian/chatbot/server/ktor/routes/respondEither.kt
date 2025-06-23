package eu.torvian.chatbot.server.ktor.routes

import arrow.core.Either
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Extension function on ApplicationCall to respond with the result of an Either.
 * Maps Left to an error response using the ApiError structure.
 *
 * @param either The result of an operation, either a success value (R) or an error (L).
 * @param successCode The HTTP status code to use for a successful (Right) response. Defaults to OK.
 * @param errorMapping A function to map the error object (L) to an ApiError object.
 *                     The HTTP status code will be taken from the ApiError object itself.
 *                     Defaults to a generic INTERNAL ApiError with status 500.
 */
suspend inline fun <reified R : Any, reified L : Any> ApplicationCall.respondEither(
    either: Either<L, R>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline errorMapping: (L) -> ApiError =
        { error -> apiError(CommonApiErrorCodes.INTERNAL, "An unexpected error occurred: $error") }
) {
    when (either) {
        is Either.Right -> respond(successCode, either.value)
        is Either.Left -> {
            val apiError = errorMapping(either.value)
            val status = HttpStatusCode.fromValue(apiError.statusCode)
            respond(status, apiError)
        }
    }
}