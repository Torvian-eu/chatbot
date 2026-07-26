package eu.torvian.chatbot.worker.builtin.net

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared mapping from the web-foundation's [WebFetchError] to a stable [BuiltInToolExecutionResult].
 *
 * Both web tools (`fetch_web_content`, `download_file`) translate fetch failures into the same
 * built-in tool error codes, so the mapping lives here once instead of being duplicated per tool.
 * Tool-specific concerns (input parsing, content-type gating, workspace writes) stay in the tools.
 *
 * @param error The logical fetch failure produced by [WebFetchService].
 * @return A structured error result carrying the appropriate stable code.
 */
fun mapWebFetchErrorToToolResult(error: WebFetchError): BuiltInToolExecutionResult = when (error) {
    is WebFetchError.InvalidUrl ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.INVALID_INPUT,
        )

    is WebFetchError.SecurityRejected ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.PERMISSION_DENIED,
        )

    is WebFetchError.Timeout ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.TIMEOUT,
        )

    is WebFetchError.TooLarge ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.EXECUTION_FAILED,
        )

    is WebFetchError.HttpError ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.EXECUTION_FAILED,
            errorDetails = buildJsonObject { put("statusCode", error.statusCode) }.toString(),
        )

    is WebFetchError.Transport ->
        BuiltInToolExecutionResult(
            isError = true,
            errorMessage = error.message,
            errorCode = BuiltInToolExecutionError.EXECUTION_FAILED,
        )
}

