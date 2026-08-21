package eu.torvian.chatbot.server.service.llm

import kotlinx.serialization.json.*

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
 * The sanitizer is applied when reasoning items cross into persistence or an in-turn follow-up context,
 * so replay receives items in the Responses API `input` shape. Replay adaptation then only changes the
 * payload according to the target model's reasoning mode and source-model provenance.
 *
 * @param reasoningItems The raw reasoning output items; empty when there are none.
 * @return The sanitized reasoning items, or an empty list when [reasoningItems] is empty.
 */
internal fun sanitizeReasoningItems(reasoningItems: List<JsonObject>): List<JsonObject> =
    reasoningItems.map(::sanitizeReasoningItem)

/**
 * Sanitizes a single raw reasoning output item into the Responses API `input` shape.
 *
 * Keeps the `input`-accepted fields `type`, `id`, `summary`, `content` and `encrypted_content`;
 * all other (output-only or provider-specific) fields are dropped. `content` and `encrypted_content`
 * are mutually exclusive on a reasoning item (an item carries exactly one of the two), so no payload
 * stripping is needed here; whether the item may be replayed to a given target is decided by
 * [adaptReasoningItemForReplay].
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
 * Adapts a previously sanitized reasoning item for the current model's reasoning mode and provenance.
 * The caller must provide an item already reduced to the Responses `input` shape by
 * [sanitizeReasoningItem] or [sanitizeReasoningItems].
 *
 * A reasoning item is only replayed **in full** — a partial shell (e.g. summary-only, or an
 * encrypted-origin item with empty plaintext `content`) is generally not sent, because providers
 * (e.g. DeepSeek thinking mode) reject reasoning items that lack the actual reasoning payload.
 * However, for plaintext targets, an encrypted-origin shell is still replayed with a space-text
 * content item so the provider sees a reasoning item (DeepSeek requires one per assistant message
 * in thinking mode). The `encrypted_content` is dropped.
 *
 * `content` and `encrypted_content` are mutually exclusive on a reasoning item, so the sanitized
 * item already holds only the payload matching its origin:
 *
 * - **Plaintext target** (`false`): replay when `content` is non-empty **or** when `encrypted_content`
 *   is present (encrypted-origin shell). In the latter case the item is replayed with a space-text
 *   `content` item and `encrypted_content` dropped so the provider sees a reasoning item (DeepSeek fix).
 * - **Encrypted target** (`true`): replay **only when the item came from the same model**
 *   (`sourceModelId == targetModelId`) **and carries a non-null `encrypted_content`**; any other
 *   source or a missing encrypted payload is not replayed (`null`).
 * - **Unknown capability** (`null`): replay as-is; we don't know the target's mode.
 *
 * @param reasoningItem A previously sanitized reasoning item to adapt.
 * @param reasoningEncrypted The target model's [eu.torvian.chatbot.common.models.llm.LLMModelCapabilities.REASONING_ENCRYPTED]
 *            value, or `null` when unknown (unknown defaults to unencrypted).
 * @param sourceModelId The ID of the model that produced the reasoning item, or `null` when unknown
 *            (e.g. the source model was deleted).
 * @param targetModelId The ID of the model now being called, which will receive the replayed item.
 * @return The sanitized item, possibly copied to adjust plaintext-target content, or `null` when the
 *         item must not be replayed at all (partial payload with no encrypted_content for plaintext targets,
 *         or encrypted target with a foreign/unknown source).
 */
internal fun adaptReasoningItemForReplay(
    reasoningItem: JsonObject,
    reasoningEncrypted: Boolean?,
    sourceModelId: Long?,
    targetModelId: Long,
): JsonObject? {
    // Explicitly encrypted target: only replay when the item came from the same model
    // and carries the opaque payload. Anything else is partial and must be skipped.
    if (reasoningEncrypted == true) {
        val sameModel = sourceModelId == targetModelId
        if (!sameModel) return null
        val hasEncryptedContent = reasoningItem["encrypted_content"]?.let { it != JsonNull } == true
        if (!hasEncryptedContent) return null
        return reasoningItem
    }
    // Explicitly plaintext target: always replay so the provider sees a reasoning item.
    // If the item has no content or is an encrypted-origin shell, ensure a space-text
    // content item is present (DeepSeek fix). The encrypted_content is dropped.
    if (reasoningEncrypted == false) {
        val hasContent = reasoningItem["content"]?.jsonArray?.isNotEmpty() == true
        // If we have an encrypted-origin shell (empty content + has encrypted_content),
        // or no content at all, add a space-text content item so the provider sees a reasoning item.
        // DeepSeek requires a space character, not an empty string.
        if (!hasContent) {
            return reasoningItem
                .withoutField("encrypted_content")
                .putField(
                    "content", JsonArray(
                        listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("reasoning_text"))
                        put("text", JsonPrimitive(" "))
                    }
                )))
        }
        return reasoningItem
    }
    // Capability unknown (null): replay as-is; we don't know the target's mode.
    return reasoningItem
}

/**
 * Returns a copy of this [JsonObject] with the given [field] replaced or added.
 *
 * @param field The key to set in the copy.
 * @param value The value to set.
 * @return A new [JsonObject] with [field] set to [value].
 */
private fun JsonObject.putField(field: String, value: JsonElement): JsonObject {
    val mutable = mutableMapOf<String, JsonElement>()
    this.entries.forEach { mutable[it.key] = it.value }
    mutable[field] = value
    return JsonObject(mutable)
}

/**
 * Returns a copy of this [JsonObject] with the given [field] removed.
 *
 * @param field The key to drop from the copy.
 * @return A new [JsonObject] containing all entries of this object except [field].
 */
private fun JsonObject.withoutField(field: String): JsonObject =
    JsonObject(filterKeys { it != field })

/**
 * Detects, from actually observed reasoning items, whether the producing model delivers its reasoning
 * as opaque `encrypted_content` payloads (encrypted-mode) or as plaintext `content` (plaintext-mode).
 *
 * The value is only ever derived from observed items and is never seeded from provider/model metadata.
 * A non-null result is only returned when the items are decisive: any non-null `encrypted_content`
 * wins immediately, otherwise non-empty plaintext `content` indicates plaintext mode. Items that carry
 * neither (e.g. only `type`/`id`/`summary`) cannot tell the mode apart and yield `null`, which means
 * "not certain — do not persist".
 *
 * @param reasoningItems The raw reasoning output items, or `null`/empty when there are none.
 * @return `true` for encrypted mode, `false` for plaintext mode, or `null` when the items are
 *         inconclusive (including an absent/empty input).
 */
internal fun detectReasoningEncryption(reasoningItems: List<JsonObject>?): Boolean? {
    if (reasoningItems.isNullOrEmpty()) return null
    val encrypted = reasoningItems.any { item ->
        val value = item["encrypted_content"]
        value != null && value != JsonNull
    }
    if (encrypted) return true
    val plaintext = reasoningItems.any { item ->
        item["content"]?.jsonArray?.isNotEmpty() == true
    }
    if (plaintext) return false
    return null // only type/id/summary items — cannot tell
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