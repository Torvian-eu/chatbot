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
 * Provider-declared failures ([LLMCompletionError.ProviderFailureError], i.e. a provider that answered 2xx and
 * then declared a non-success ending) are classified from the provider's own code instead, because no HTTP
 * status exists for them. The provider code selects the family (authentication, rate limit, output limit, …)
 * and is never persisted; the variants' server-authored `message` is likewise ignored, so a provider cannot
 * influence the persisted text through either field.
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

    is LLMCompletionError.ProviderFailureError -> toAssistantMessageCompletionState()

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
 * Classifies a provider-declared non-success ending into a failure state.
 *
 * Only the provider's classification token is used, matched case-insensitively because providers are not
 * consistent about its casing. Token families map to existing codes; `max_output_tokens` has its own code
 * ([AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED]) because a
 * provider-side output limit is a different situation from the server's own assistant-message character cap.
 *
 * Unknown tokens, tokens the provider omits entirely, and a `cancelled` status all degrade to the
 * `PROVIDER_UNAVAILABLE` default ("The provider failed to generate a response."), which is coarse but honest: the
 * provider did not produce a generation for us to classify further, and the raw token is always in the server
 * logs. [LLMCompletionError.ProviderFailureError.message] is deliberately ignored — the persisted reason is one
 * of the templates below, so no provider-authored text can reach it.
 *
 * @receiver The provider-declared failure described by its provider code.
 * @return A `FAILED` completion state with the mapped code and a bounded, server-authored reason.
 */
private fun LLMCompletionError.ProviderFailureError.toAssistantMessageCompletionState(): AssistantMessageCompletionState {
    return when (providerCode?.lowercase(Locale.ROOT)) {
        in PROVIDER_AUTHENTICATION_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
            message = "The provider rejected the API key or credentials."
        )
        in PROVIDER_QUOTA_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.RATE_LIMITED,
            message = "The provider account has no remaining quota."
        )
        in PROVIDER_RATE_LIMIT_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.RATE_LIMITED,
            message = "The provider is rate limiting requests. Try again in a moment."
        )
        in PROVIDER_OUTPUT_LIMIT_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
            // Distinct from the server's own character cap, so the two limits can never be confused.
            message = "The provider stopped the response because the model reached its output limit."
        )
        in PROVIDER_CONTEXT_LENGTH_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            message = "The request was longer than the model's context window."
        )
        in PROVIDER_CONTENT_POLICY_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            message = "The provider stopped the response because of its content policy."
        )
        in PROVIDER_REQUEST_REJECTED_CODES -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            message = "The provider rejected the request."
        )
        in PROVIDER_INCOMPLETE_STATUS_TOKENS -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.STREAM_INTERRUPTED,
            // The provider is answering, so an unavailability reason would misdescribe a generation it merely
            // ended early; only the outcome is known, not the cause.
            message = "The provider ended the response before it finished."
        )
        else -> AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            message = "The provider failed to generate a response."
        )
    }
}

/**
 * Reports the non-success ending a provider's own stop token declares.
 *
 * A stop token is how the Chat Completions-style dialects say how a generation ended, and the token becomes the
 * [LLMCompletionError.ProviderFailureError.providerCode] that [toAssistantMessageCompletionState] classifies, so
 * every dialect and both call paths report the same ending for the same token.
 *
 * @param stopToken Stop token of a finished choice (`length`, `content_filter`, `stop`, `tool_calls`, …), or
 *        `null` when the dialect does not report one.
 * @return The ending to report, or `null` when the token means the generation completed normally.
 */
internal fun providerDeclaredEndingError(stopToken: String?): LLMCompletionError.ProviderFailureError? {
    val normalizedStopToken = stopToken?.lowercase(Locale.ROOT) ?: return null
    if (normalizedStopToken !in PROVIDER_INCOMPLETE_STOP_TOKENS) return null
    return LLMCompletionError.ProviderFailureError(
        providerCode = normalizedStopToken,
        message = "The provider ended the response without completing it."
    )
}

/**
 * Provider codes meaning the provider refused our credentials.
 *
 * Maps to `AUTHENTICATION_FAILED`, the same classification an HTTP 401/403 gets.
 */
private val PROVIDER_AUTHENTICATION_CODES: Set<String> = setOf(
    "invalid_api_key",
    "authentication_error",
    "invalid_organization",
    "account_deactivated"
)

/**
 * Provider codes meaning the provider is throttling us.
 *
 * Maps to `RATE_LIMITED`, the same classification an HTTP 429 gets. `too_many_requests` is the code some
 * OpenAI-compatible providers use for the same condition.
 */
private val PROVIDER_RATE_LIMIT_CODES: Set<String> = setOf(
    "rate_limit_exceeded",
    "too_many_requests"
)

/**
 * Provider codes meaning the provider account ran out of credit/allowance.
 *
 * Folded into `RATE_LIMITED` because no code describes billing, and "the operator has to act / try later" is
 * closer to rate limiting than to a rejected request; the persisted reason names the quota so the two are
 * distinguishable.
 */
private val PROVIDER_QUOTA_CODES: Set<String> = setOf(
    "insufficient_quota",
    "billing_hard_limit_reached"
)

/**
 * Provider codes meaning the model hit the provider's own output limit and the answer was cut off.
 *
 * Maps to its own `PROVIDER_OUTPUT_LIMIT_EXCEEDED` code, which is deliberately not shared with the server's
 * assistant-message character cap (`OUTPUT_LIMIT_EXCEEDED`). `length` is the Chat Completions/Ollama stop token
 * for the same situation the Responses API describes with `max_output_tokens`.
 */
private val PROVIDER_OUTPUT_LIMIT_CODES: Set<String> = setOf(
    "max_output_tokens",
    "length"
)

/**
 * Stop tokens that mean the provider cut a generation short instead of finishing it.
 *
 * Both are reported as a failure so an incomplete answer is never persisted as a completed one; any other token
 * (`stop`, `tool_calls`, or none at all) means the generation ended normally.
 */
private val PROVIDER_INCOMPLETE_STOP_TOKENS: Set<String> = setOf(
    "length",
    "content_filter"
)

/**
 * Provider codes meaning the provider declared the generation incomplete without disclosing a cause of its own.
 *
 * The status says only *that* the generation ended early, so it is classified as an interrupted generation: no
 * evidence points to the provider being unavailable, and the answer may be cut off mid-generation.
 */
private val PROVIDER_INCOMPLETE_STATUS_TOKENS: Set<String> = setOf(
    "incomplete"
)

/**
 * Provider codes meaning the request was longer than the model's context window.
 *
 * Maps to `PROVIDER_REQUEST_REJECTED` with a reason naming the context window.
 */
private val PROVIDER_CONTEXT_LENGTH_CODES: Set<String> = setOf(
    "context_length_exceeded"
)

/**
 * Provider codes meaning the provider stopped the generation because of its content policy.
 *
 * Maps to `PROVIDER_REQUEST_REJECTED` with a reason naming the content policy, so a policy stop is not read as a
 * malformed request.
 */
private val PROVIDER_CONTENT_POLICY_CODES: Set<String> = setOf(
    "content_filter",
    "content_policy_violation"
)

/**
 * Provider codes meaning the provider rejected the request itself (unknown model, malformed payload, a
 * message-count limit, …).
 *
 * Maps to `PROVIDER_REQUEST_REJECTED`, the same classification an HTTP 4xx other than 401/403/429 gets. Codes
 * outside every family — including `server_error`, `timeout`, `connection_error`, a `cancelled` status and any
 * future token — fall through to the `PROVIDER_UNAVAILABLE` default instead of being reported as our fault.
 */
private val PROVIDER_REQUEST_REJECTED_CODES: Set<String> = setOf(
    "invalid_request_error",
    "invalid_prompt",
    "unsupported_value",
    "max_messages"
)

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
