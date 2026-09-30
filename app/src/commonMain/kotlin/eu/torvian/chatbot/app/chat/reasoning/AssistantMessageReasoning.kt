package eu.torvian.chatbot.app.chat.reasoning

import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Character bound applied to each block of reasoning text derived for display.
 *
 * The bound is presentation-only: the cached and persisted reasoning keeps every character it received, so
 * reopening a session renders exactly the same prefix and the cut is announced in the UI instead of being silent.
 */
internal const val MAX_RENDERED_REASONING_CHARS: Int = 32_000

/**
 * What the reasoning section of one assistant message should show.
 *
 * @property summaryText Summary the model published for its reasoning, or an empty string when absent.
 * @property reasoningText Plaintext reasoning, or an empty string when the model delivered none (for example an
 *            encrypted-only response).
 * @property showsEncryptedNotice Whether the message carries encrypted reasoning and no plaintext reasoning text
 *            to show instead, i.e. whether the UI has to explain that the reasoning cannot be displayed.
 * @property isTruncated Whether at least one displayed block was cut at [MAX_RENDERED_REASONING_CHARS].
 * @property showsThinkingIndicator Whether the header carries the thinking indicator: the turn is still running and
 *            no answer text has arrived, so the generation is still in its reasoning phase.
 */
internal data class ReasoningDisplay(
    val summaryText: String,
    val reasoningText: String,
    val showsEncryptedNotice: Boolean,
    val isTruncated: Boolean,
    val showsThinkingIndicator: Boolean
)

/**
 * Appends a live reasoning-text delta to the reasoning items cached for the streaming message.
 *
 * The delta extends the trailing streamed-text item when the list already ends with one, and otherwise appends a
 * new item in the same Responses shape the server uses, so the cached list stays faithful to the payload the
 * completing event will replace it with. Only the server-side cap bounds this accumulation; the list is not capped
 * here, because shortening the cache would hide characters a later reload would show.
 *
 * @param delta Reasoning text received on the stream.
 * @return The updated items. A blank delta — empty or whitespace-only — is dropped, agreeing with the server-side
 *         persistence of the same text on what counts as reasoning.
 */
internal fun List<JsonObject>?.appendReasoningTextDelta(delta: String): List<JsonObject> {
    val items = this.orEmpty()
    if (delta.isBlank()) return items
    val trailingItem = items.lastOrNull()
    if (trailingItem != null && trailingItem.isStreamedReasoningTextItem()) {
        return items.dropLast(1) + trailingItem.withAppendedStreamedText(delta)
    }
    return items + newStreamedReasoningTextItem(delta)
}

/**
 * Derives the reasoning to display for this assistant message, or `null` when it has none.
 *
 * Only the derived plaintext of the items is read (`summary[].text`, `content[].text`): the raw item JSON and any
 * `encrypted_content` payload never reach the display model, so they can never be rendered.
 *
 * @receiver Assistant message whose reasoning items should be displayed.
 * @return The blocks to show, each cut at [MAX_RENDERED_REASONING_CHARS], or `null` when the message has nothing to
 *         show. The `null` is what keeps a message that received no reasoning from rendering an empty section.
 */
internal fun ChatMessage.AssistantMessage.deriveReasoningDisplay(): ReasoningDisplay? {
    val items = reasoningItems.orEmpty()
    if (items.isEmpty()) return null
    val (summaryText, summaryCut) = items.joinPlaintext("summary").cutToRenderLimit()
    val (reasoningText, reasoningCut) = items.joinPlaintext("content").cutToRenderLimit()
    val hasEncryptedContent = items.any { item ->
        val encrypted = item["encrypted_content"]
        encrypted != null && encrypted != JsonNull
    }
    // An encrypted payload is only announced when there is no plaintext text to show: the notice must never
    // contradict reasoning that is rendered right below it.
    val showsEncryptedNotice = hasEncryptedContent && reasoningText.isBlank()
    // Items that carry neither plaintext nor an opaque payload describe nothing, so they show no section either.
    if (summaryText.isBlank() && reasoningText.isBlank() && !showsEncryptedNotice) return null
    return ReasoningDisplay(
        summaryText = summaryText,
        reasoningText = reasoningText,
        showsEncryptedNotice = showsEncryptedNotice,
        isTruncated = summaryCut || reasoningCut,
        // Providers emit reasoning before the answer, so an answer that is still empty says the model is still
        // reasoning. The indicator also stops when the turn reaches a terminal state, which covers a turn that ends
        // without ever producing answer text.
        showsThinkingIndicator = !isComplete && incompleteCause == null && content.isBlank()
    )
}

/**
 * Concatenates the `text` parts of [field] over all items, separating items with a blank line.
 *
 * Parts of one item are concatenated directly because a provider may split a single reasoning text into several
 * parts; items are separated because each one is a distinct chain of thought.
 *
 * @param field Name of the item field to read (`summary` or `content`).
 * @return The joined plaintext, or an empty string when no item carries any.
 */
private fun List<JsonObject>.joinPlaintext(field: String): String =
    mapNotNull { item ->
        (item[field] as? JsonArray)
            ?.mapNotNull { part -> ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
            ?.joinToString("")
    }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

/**
 * Whether this item is the plaintext placeholder the client maintains while reasoning streams.
 *
 * The check is shape-based, because the placeholder is deliberately indistinguishable from a persisted
 * plaintext item: it is a reasoning item without an opaque payload whose content parts are reasoning text. An
 * encrypted item and a summary-only item are therefore excluded, while a trailing plaintext item is extended.
 */
private fun JsonObject.isStreamedReasoningTextItem(): Boolean {
    if ((this["type"] as? JsonPrimitive)?.contentOrNull != "reasoning") return false
    val encrypted = this["encrypted_content"]
    if (encrypted != null && encrypted != JsonNull) return false
    val parts = this["content"] as? JsonArray ?: return false
    return parts.isNotEmpty() && parts.all { part ->
        (part as? JsonObject)?.let { (it["type"] as? JsonPrimitive)?.contentOrNull == "reasoning_text" } == true
    }
}

/**
 * Returns a copy of this streamed-text item with [delta] appended to its last text part.
 *
 * Appending instead of replacing keeps the item shape stable while the delta stream grows, so every
 * recomposition derives the text from one growing item rather than from a growing list.
 *
 * @param delta Reasoning text received on the stream.
 * @return The updated item, or this item when it has no text part to extend.
 */
private fun JsonObject.withAppendedStreamedText(delta: String): JsonObject {
    val parts = this["content"] as? JsonArray ?: return this
    val lastPart = parts.lastOrNull() as? JsonObject ?: return this
    val existingText = (lastPart["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val updatedParts = parts.toMutableList()
    updatedParts[updatedParts.lastIndex] = JsonObject(
        lastPart + ("text" to JsonPrimitive(existingText + delta))
    )
    return JsonObject(this + ("content" to JsonArray(updatedParts.toList())))
}

/**
 * Builds the streamed-text item in the Responses shape used by the server-side persistence of the same text.
 *
 * @param delta Reasoning text received on the stream.
 * @return A reasoning item holding a single `reasoning_text` part.
 */
private fun newStreamedReasoningTextItem(delta: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("reasoning"))
    put(
        "content",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("reasoning_text"))
                    put("text", JsonPrimitive(delta))
                }
            )
        }
    )
}

/**
 * Cuts this block to the display bound.
 *
 * @return The block and whether it had to be cut.
 */
private fun String.cutToRenderLimit(): Pair<String, Boolean> =
    if (length > MAX_RENDERED_REASONING_CHARS) {
        take(MAX_RENDERED_REASONING_CHARS) to true
    } else {
        this to false
    }
