package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import java.util.Locale

/**
 * Maps a provider/LLM failure to the failure state persisted with the affected assistant message.
 *
 * This function and its siblings below are the **only** place where a technical failure becomes user-facing
 * text: every message is a fixed template (optionally completed with a status code or a server-authored
 * configuration description), so provider error bodies, provider `message` fields and exception messages stay in
 * server logs. Assistant messages are visible to every user with access to the session, which is why nothing
 * provider-internal may be persisted. All results are built through [AssistantMessageCompletionState.failed],
 * which bounds the text; the raw error is still logged by the caller, so diagnostics are not lost.
 *
 * Status-code-derived classifications: `401`/`403` are authentication failures, `429` is rate limiting, other
 * `4xx` are request rejections and `5xx` (or unexpected statuses) mean the provider is unavailable.
 *
 * @receiver The failure reported by the LLM client.
 * @return A `FAILED` completion state carrying a machine-readable code and a bounded, provider-internal-free
 *         reason. Never contains provider response bodies or exception text.
 */
fun LLMCompletionError.toAssistantMessageCompletionState(): AssistantMessageCompletionState = when (this) {
    is LLMCompletionError.AuthenticationError -> AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
        message = "The provider rejected the API key or credentials."
    )

    is LLMCompletionError.NetworkError -> AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
        message = "The provider could not be reached because of a network error."
    )

    is LLMCompletionError.ApiError -> toAssistantMessageCompletionState()

    is LLMCompletionError.InvalidResponseError -> AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.INVALID_PROVIDER_RESPONSE,
        message = "The provider returned a response that could not be processed."
    )

    // The configuration message is authored by server-side strategies (unsupported provider type,
    // incompatible settings, missing API key), so it is safe to surface — unlike provider output.
    is LLMCompletionError.ConfigurationError -> AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.CONFIGURATION_ERROR,
        message = "The request could not be sent because the model configuration is invalid: $message"
    )

    is LLMCompletionError.OtherError -> AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.UNEXPECTED_ERROR,
        message = "The response was interrupted by an unexpected server error."
    )
}

/**
 * Classifies a provider HTTP error response into a failure state.
 *
 * Only the status code is persisted: [LLMCompletionError.ApiError.message] and
 * [LLMCompletionError.ApiError.errorBody] contain provider-supplied text and must stay in the logs.
 *
 * @receiver The status-code-carrying provider error.
 * @return A `FAILED` completion state whose reason mentions the status code but never the provider payload.
 */
private fun LLMCompletionError.ApiError.toAssistantMessageCompletionState(): AssistantMessageCompletionState =
    when (statusCode) {
        401, 403 -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
            message = "The provider rejected the API key or credentials (HTTP $statusCode)."
        )

        429 -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.RATE_LIMITED,
            message = "The provider is rate limiting requests (HTTP $statusCode). Try again in a moment."
        )

        in 400..499 -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            message = "The provider rejected the request (HTTP $statusCode)."
        )

        in 500..599 -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            message = "The provider is currently unavailable (HTTP $statusCode)."
        )

        else -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            message = "The provider returned an unexpected response status (HTTP $statusCode)."
        )
    }

/**
 * Builds the failure state for a response that was cut off at the assistant message character limit.
 *
 * The message content is cut at the cap and flagged, so the limit is reported as a failure reason of the message
 * instead of as a notice inside its content.
 *
 * @param limitChars The character limit that was exceeded, echoed in the reason for operator clarity.
 * @return A `FAILED` completion state with code `OUTPUT_LIMIT_EXCEEDED` and a bounded reason naming the limit.
 */
fun outputLimitExceededCompletionState(limitChars: Int): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED,
        // Locale.ROOT keeps the digit grouping deterministic (`64,000`) regardless of the server locale.
        message = "The response was stopped because it exceeded the ${
            String.format(Locale.ROOT, "%,d", limitChars)
        }-character limit."
    )

/**
 * Builds the failure state for a tool-calling iteration that could not be followed up because the turn reached
 * the assistant/tool iteration limit.
 *
 * The limit is turn-level, but the failure is recorded on the message of the final iteration, because that is the
 * message whose tool calls the turn could no longer react to. Its calls are still submitted for execution; only the
 * follow-up request the loop would normally make is gone.
 *
 * @param limitIterations The iteration limit that was reached, echoed in the reason for operator clarity.
 * @return A `FAILED` completion state with code `TOOL_CALL_ITERATION_LIMIT_EXCEEDED` and a bounded reason.
 */
fun toolCallIterationLimitExceededCompletionState(limitIterations: Int): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.TOOL_CALL_ITERATION_LIMIT_EXCEEDED,
        message = "The turn was stopped because the assistant reached the limit of " +
            "$limitIterations tool-calling steps. The tool calls of the last step were still executed."
    )

/**
 * Builds the failure state for an assistant step that asked for more tool calls than a single step accepts.
 *
 * A batch that large means something went wrong with the response, so none of its calls are acted upon: the step
 * drops all of them and the reason says so, because the message reason is the one place where the user can learn
 * that the requested work did not run.
 *
 * @param limitToolCalls The per-step tool-call limit, echoed in the reason for operator clarity.
 * @return A `FAILED` completion state with code `TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED` and a bounded reason.
 */
fun toolCallsPerStepLimitExceededCompletionState(limitToolCalls: Int): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED,
        message = "The turn was stopped because the assistant asked for more than " +
            "$limitToolCalls tool calls in a single step. None of the requested tool calls were executed."
    )

/**
 * Builds the failure state for a tool call whose argument payload exceeded the per-call character limit.
 *
 * The payload is JSON, so the cap can only cut it into input the model never sent. That call alone is therefore not
 * executed, while the other calls of the step still run (including the ones that arrived after it), and the reason
 * states that, so the user can tell a partial batch from a batch that never ran.
 *
 * @param limitChars The per-argument character limit that was exceeded, echoed in the reason.
 * @return A `FAILED` completion state with code `TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED` and a bounded reason.
 */
fun toolCallArgumentLimitExceededCompletionState(limitChars: Int): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,
        // Locale.ROOT keeps the digit grouping deterministic (`300,000`) regardless of the server locale.
        message = "The turn was stopped because a tool call argument exceeded the ${
            String.format(Locale.ROOT, "%,d", limitChars)
        }-character limit. That tool call was not executed; the other tool calls of the step were still executed."
    )

/**
 * Builds the failure state for an unexpected server-side error while the message was still in flight.
 *
 * @return A `FAILED` completion state with code `UNEXPECTED_ERROR` and a generic, provider-internal-free reason.
 */
fun unexpectedFailureCompletionState(): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.UNEXPECTED_ERROR,
        message = "The response was interrupted by an unexpected server error."
    )

/**
 * Builds the failure state for a provider stream that ended without any terminal signal (no completion chunk,
 * error or cancellation), which leaves an answer that may be cut off mid-generation.
 *
 * @return A `FAILED` completion state with code `STREAM_INTERRUPTED` and a generic reason.
 */
fun streamInterruptedCompletionState(): AssistantMessageCompletionState =
    AssistantMessageCompletionState.failed(
        code = AssistantMessageErrorCode.STREAM_INTERRUPTED,
        message = "The response stream ended before it was completed."
    )
