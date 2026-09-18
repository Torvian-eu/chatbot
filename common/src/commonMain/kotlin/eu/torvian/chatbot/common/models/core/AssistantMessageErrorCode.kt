package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable

/**
 * Machine-readable classification of why an assistant message failed.
 *
 * Only set together with [AssistantMessageIncompleteCause.FAILED]: a failure always carries one of
 * these codes plus a bounded, provider-internal-free reason text on
 * [ChatMessage.AssistantMessage.errorMessage]. Raw provider bodies, HTTP error payloads and exception
 * messages are deliberately never persisted (they stay in server logs, while messages are visible to
 * every user with access to the session), so clients must treat codes as the stable classification and
 * the reason as untrusted display text.
 *
 * The codes are persisted and sent by enum name.
 */
@Serializable
enum class AssistantMessageErrorCode {
    /**
     * The provider rejected the API key or credentials (provider authentication error, or an HTTP
     * 401/403 response).
     */
    AUTHENTICATION_FAILED,

    /** The provider is rate limiting requests (HTTP 429). Retrying later may succeed. */
    RATE_LIMITED,

    /**
     * The provider rejected the request itself (HTTP 4xx other than 401/403/429), typically because
     * the model, settings or payload were not acceptable to it.
     */
    PROVIDER_REQUEST_REJECTED,

    /**
     * The provider was unreachable or failing on its side: a network/transport error, or an HTTP 5xx
     * (or otherwise unexpected) response status.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The provider answered with a success status whose body could not be parsed into a completion
     * (malformed payload, or a successful response without any choice).
     */
    INVALID_PROVIDER_RESPONSE,

    /**
     * The request could not be sent at all because the server-side model/provider configuration was
     * invalid (unsupported provider type, incompatible settings, a missing API key, and similar).
     */
    CONFIGURATION_ERROR,

    /**
     * The generated text exceeded the server's assistant message character limit, so it was cut off.
     * The message is flagged instead of embedding a truncation notice in its content.
     */
    OUTPUT_LIMIT_EXCEEDED,

    /**
     * The turn reached the server's limit of assistant/tool iterations, so the model could not be asked
     * to react to the tool calls of the final allowed iteration. Those calls are nevertheless persisted,
     * approved and executed before the turn ends; only the follow-up model call is gone.
     */
    TOOL_CALL_ITERATION_LIMIT_EXCEEDED,

    /**
     * The assistant asked for more tool calls in a single step than the server accepts, so the turn was
     * stopped instead of acting on a partial batch: none of the step's calls was persisted, approved or
     * executed.
     */
    TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED,

    /**
     * A tool call argument exceeded the server's per-call character limit, so that call's argument could
     * not be persisted intact. Only that call was discarded; every other tool call of the step is still
     * persisted, approved and executed, including the ones that arrived after it.
     */
    TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,

    /**
     * The provider's response stream ended without a completion signal (no terminal chunk, no error
     * and no cancellation), so the answer may be cut off mid-generation.
     */
    STREAM_INTERRUPTED,

    /**
     * The generation ended because of an unexpected server-side error that has no more specific
     * classification.
     */
    UNEXPECTED_ERROR
}
