package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import io.ktor.http.HttpHeaders

/**
 * Maximum number of API-key characters the masked form may expose.
 *
 * The mask also never exposes more than half of the key, so this cap is only reached by keys of
 * 17 characters or more.
 */
private const val MAX_MASKED_KEY_PREFIX_CHARS = 8

/** Fixed replacement used when a key is too short to reveal any part of itself. */
private const val SHORT_KEY_MASK = "****"

/** Marker appended after the revealed prefix of a masked key. */
private const val MASK_ELLIPSIS = "..."

/**
 * Builds the wire payload that describes the request this configuration would dispatch.
 *
 * The payload is derived from the same values that are sent over the wire: [baseUrl] joined with
 * [ApiRequestConfig.path] for the target URL, [ApiRequestConfig.method] for the verb,
 * [ApiRequestConfig.customHeaders] plus the request's own content type for the headers, and
 * [ApiRequestConfig.body] as the pre-serialized body text.
 *
 * [apiKey] is masked inside the URL and inside every header value, so the returned payload never
 * contains the raw credential regardless of the header name a strategy used. A null key leaves the
 * values untouched and creates no header, because strategies only add an `Authorization` header when
 * a key exists.
 *
 * @param baseUrl Provider base URL the strategy path is resolved against.
 * @param apiKey Raw API key in use, or null when the provider needs none.
 * @return The masked, wire-ready description of the prepared request.
 */
internal fun ApiRequestConfig.toRawLlmRequestPayload(
    baseUrl: String,
    apiKey: String?
): RawLlmRequestPayload {
    val headers = customHeaders +
            (HttpHeaders.ContentType to contentType.contentType)
    val bodyText = body.toString()

    return RawLlmRequestPayload(
        method = method.name,
        url = (baseUrl + path).maskKey(apiKey),
        headers = headers.mapValues { (_, value) -> value.maskKey(apiKey) },
        body = bodyText
    )
}

/**
 * Replaces every occurrence of [apiKey] in this value with its masked form.
 *
 * @param apiKey Raw API key to hide, or null to return the value unchanged.
 * @return The value with the credential masked.
 */
private fun String.maskKey(apiKey: String?): String {
    if (apiKey.isNullOrEmpty()) return this
    return replace(apiKey, maskApiKey(apiKey))
}

/**
 * Masks an API key so it can be shown to the session owner without revealing the credential.
 *
 * A key of at most [MAX_MASKED_KEY_PREFIX_CHARS] characters is replaced entirely by [SHORT_KEY_MASK]
 * because any exposure would reveal a substantial fraction of it. A longer key keeps at most its
 * first `min(MAX_MASKED_KEY_PREFIX_CHARS, key.length / 2)` characters followed by [MASK_ELLIPSIS],
 * so never more than half of the key is exposed.
 *
 * @param apiKey Raw API key to mask.
 * @return The masked key, with no raw character beyond the allowed prefix.
 */
private fun maskApiKey(apiKey: String): String {
    if (apiKey.length <= MAX_MASKED_KEY_PREFIX_CHARS) return SHORT_KEY_MASK

    val prefixLength = minOf(MAX_MASKED_KEY_PREFIX_CHARS, apiKey.length / 2)
    return apiKey.take(prefixLength) + MASK_ELLIPSIS
}
