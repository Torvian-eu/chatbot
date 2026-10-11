package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Provider-neutral wire payload carrying the request that was about to be sent to the LLM provider
 * on the `raw_llm_request` event of both chat surfaces (the non-streaming [ChatEvent] stream and the
 * streaming [ChatStreamEvent] stream).
 *
 * The payload is a diagnostic view of one provider call, built by the server just before dispatch.
 * It is not part of the transcript and must never be persisted or rendered as a message.
 *
 * **Wire contract:**
 * - [method] is the HTTP verb of the dispatch (for example `POST`).
 * - [url] is the exact dispatch target, that is the provider base URL joined with the strategy path.
 * - [headers] holds the strategy's custom headers plus the request's `Content-Type`. Any credential
 *   in a header value is already masked by the server, so clients never receive the raw API key;
 *   a keyless provider simply has no `Authorization` entry.
 * - [body] is the request body as pre-serialized JSON text.
 *
 * Anything a viewer derives from these fields, such as the body's byte size, is computed by the
 * receiving client so the payload stays exactly the request that was dispatched.
 *
 * @property method HTTP verb used for the provider call.
 * @property url Full URL the request was dispatched to.
 * @property headers Request headers, including the content type and an already-masked credential.
 * @property body Pre-serialized JSON body text of the request.
 */
@Serializable
data class RawLlmRequestPayload(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)
