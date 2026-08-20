package eu.torvian.chatbot.server.service.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Sanitizes raw reasoning output items so they can be safely replayed into a future Responses API
 * `input` array.
 *
 * Providers (e.g. OpenRouter) emit reasoning output items that carry output-only fields — such as
 * `status` or `format` — which OpenAI's Responses API rejects as unknown parameters when they are
 * fed back into `input` (e.g. `Unknown parameter: 'input[1].status'`). This normalizer keeps only
 * the fields the Responses `input` schema accepts for `type: "reasoning"` items (`type`, `id`,
 * `summary`, `content`, `encrypted_content`) and, within each summary/content part, only `type` and
 * `text`. All other fields are dropped.
 *
 * `encrypted_content` (the opaque, stateless-mode reasoning payload) is deliberately preserved
 * verbatim: OpenAI requires it to be replayed on subsequent turns when `store: false` or ZDR is
 * used, so it must survive the round-trip. `status` and `format` are output-only/provider-specific
 * and are never accepted in `input`.
 *
 * The sanitizer is applied both when reasoning items are persisted (so the database only ever stores
 * replay-safe items) and again when they are replayed (so previously stored, unsanitized rows cannot
 * break future requests).
 *
 * @param reasoningItems The raw reasoning output items, or `null`/empty when there are none.
 * @return The sanitized items, mirroring a `null`/empty input, or `null` when there are none.
 */
internal fun sanitizeReasoningItems(reasoningItems: List<JsonObject>?): List<JsonObject>? =
    reasoningItems?.map(::sanitizeReasoningItem)

/**
 * Sanitizes a single raw reasoning output item into the Responses API `input` shape.
 *
 * Keeps the `input`-accepted fields `type`, `id`, `summary`, `content` and `encrypted_content`;
 * all other (output-only or provider-specific) fields are dropped.
 *
 * @param reasoningItem The raw reasoning output item (e.g. `{"type":"reasoning",...}`).
 * @return A new [JsonObject] containing only the `input`-accepted fields of [reasoningItem].
 */
internal fun sanitizeReasoningItem(reasoningItem: JsonObject): JsonObject = buildJsonObject {
    reasoningItem["type"]?.let { put("type", it) }
    reasoningItem["id"]?.let { put("id", it) }
    reasoningItem["summary"]?.let { put("summary", sanitizeReasoningParts(it)) }
    reasoningItem["content"]?.let { put("content", sanitizeReasoningParts(it)) }
    // Encrypted (opaque) reasoning is replayed as-is in stateless mode; never strip it.
    reasoningItem["encrypted_content"]?.let { put("encrypted_content", it) }
}

/**
 * Reduces a reasoning part collection (a `summary` or `content` array) to the fields accepted by the
 * Responses `input` schema.
 *
 * @param parts The raw part collection.
 * @return A new [JsonArray] containing only the `type`/`text` fields of each object part. Non-object
 *         entries are dropped; non-array inputs are passed through unchanged.
 */
private fun sanitizeReasoningParts(parts: JsonElement): JsonElement =
    if (parts is JsonArray) {
        JsonArray(
            parts.mapNotNull { part ->
                (part as? JsonObject)?.let { obj ->
                    buildJsonObject {
                        obj["type"]?.let { put("type", it) }
                        obj["text"]?.let { put("text", it) }
                    }
                }
            }
        )
    } else {
        parts
    }
