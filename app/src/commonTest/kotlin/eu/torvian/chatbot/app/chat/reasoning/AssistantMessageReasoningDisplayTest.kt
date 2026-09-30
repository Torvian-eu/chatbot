package eu.torvian.chatbot.app.chat.reasoning

import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the pure derivation that feeds the reasoning section and the live append used while it streams.
 *
 * The derivation is the only place where persisted reasoning becomes visible, so it must read plaintext only,
 * decide the encrypted notice without ever contradicting rendered text, and keep the display cap separate from the
 * stored payload.
 */
class AssistantMessageReasoningDisplayTest {

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /**
     * Builds an assistant message carrying the given reasoning items.
     *
     * @param items Reasoning items as they are persisted or cached.
     * @param content Answer text already received; empty while the model is still reasoning.
     * @param isComplete Whether the message reached a completed state.
     * @param incompleteCause Terminal cause of a non-completion, or `null` while the message is in flight.
     * @return Assistant message whose reasoning is [items].
     */
    private fun assistantMessage(
        items: List<JsonObject>?,
        content: String = "answer",
        isComplete: Boolean = true,
        incompleteCause: AssistantMessageIncompleteCause? = null
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = 1L,
        sessionId = 1L,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList(),
        modelId = 1L,
        settingsId = 1L,
        reasoningItems = items,
        isComplete = isComplete,
        incompleteCause = incompleteCause
    )

    @Test
    fun `deriveReasoningDisplay is null for a message with no reasoning to show`() {
        assertNull(assistantMessage(null).deriveReasoningDisplay())
        assertNull(assistantMessage(emptyList()).deriveReasoningDisplay())
        // Items that carry neither plaintext nor an encrypted payload describe nothing to display.
        val unreadable = buildJsonObject { put("type", JsonPrimitive("reasoning")) }
        assertNull(assistantMessage(listOf(unreadable)).deriveReasoningDisplay())
    }

    @Test
    fun `the thinking indicator follows the reasoning phase and the turn state`() {
        val items = listOf(reasoningItem(contentText = listOf("Chain of thought")))

        // While the turn runs and no answer has arrived, the generation is still reasoning.
        val reasoning = assertNotNull(
            assistantMessage(items, content = "", isComplete = false).deriveReasoningDisplay()
        )
        assertEquals("Chain of thought", reasoning.reasoningText)
        assertTrue(reasoning.showsThinkingIndicator)

        // Providers emit reasoning before the answer, so the first answer text means the model moved on and the
        // indicator goes away even though the turn is still running.
        assertFalse(
            assertNotNull(
                assistantMessage(items, content = "answer", isComplete = false).deriveReasoningDisplay()
            ).showsThinkingIndicator
        )

        // A terminal state stops it too, including a turn that never produced answer text.
        assertFalse(
            assertNotNull(assistantMessage(items, content = "", isComplete = true).deriveReasoningDisplay())
                .showsThinkingIndicator
        )
        assertFalse(
            assertNotNull(
                assistantMessage(
                    items,
                    content = "",
                    isComplete = false,
                    incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
                ).deriveReasoningDisplay()
            ).showsThinkingIndicator
        )
    }

    @Test
    fun `an encrypted-only message carries the notice and the indicator while it reasons`() {
        val items = listOf(reasoningItem(encryptedContent = "opaque"))

        val inFlight = assertNotNull(
            assistantMessage(items, content = "", isComplete = false).deriveReasoningDisplay()
        )

        assertTrue(inFlight.showsEncryptedNotice)
        assertEquals("", inFlight.reasoningText)
        assertTrue(inFlight.showsThinkingIndicator)
    }

    /**
     * Builds a reasoning item from raw part fields.
     *
     * @param summaryText Parts of the `summary` array, in order.
     * @param contentText Parts of the `content` array, in order.
     * @param encryptedContent Opaque payload to attach, or `null` for a plaintext-origin item.
     * @return Reasoning item holding exactly the requested fields.
     */
    private fun reasoningItem(
        summaryText: List<String> = emptyList(),
        contentText: List<String> = emptyList(),
        encryptedContent: String? = null
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("reasoning"))
        if (summaryText.isNotEmpty()) {
            put(
                "summary",
                JsonArray(summaryText.map { textPart("summary_text", it) })
            )
        }
        if (contentText.isNotEmpty()) {
            put(
                "content",
                JsonArray(contentText.map { textPart("reasoning_text", it) })
            )
        }
        if (encryptedContent != null) {
            put("encrypted_content", JsonPrimitive(encryptedContent))
        }
    }

    /**
     * Builds one `{type, text}` part.
     *
     * @param type Part type.
     * @param text Part text.
     * @return The part object.
     */
    private fun textPart(type: String, text: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("text", JsonPrimitive(text))
    }

    @Test
    fun `summary and content of one item are both rendered`() {
        val display = assertNotNull(
            assistantMessage(
                listOf(reasoningItem(summaryText = listOf("Short summary"), contentText = listOf("Chain of thought")))
            ).deriveReasoningDisplay()
        )

        assertEquals("Short summary", display.summaryText)
        assertEquals("Chain of thought", display.reasoningText)
        assertFalse(display.showsEncryptedNotice)
    }

    @Test
    fun `content-only reasoning renders text without a summary`() {
        val display = assertNotNull(
            assistantMessage(listOf(reasoningItem(contentText = listOf("Only text")))).deriveReasoningDisplay()
        )

        assertEquals("", display.summaryText)
        assertEquals("Only text", display.reasoningText)
    }

    @Test
    fun `summary-only reasoning renders the summary and no notice`() {
        val display = assertNotNull(
            assistantMessage(listOf(reasoningItem(summaryText = listOf("Just the summary")))).deriveReasoningDisplay()
        )

        assertEquals("Just the summary", display.summaryText)
        assertEquals("", display.reasoningText)
        // A summary without any encrypted payload is not an encrypted reasoning.
        assertFalse(display.showsEncryptedNotice)
    }

    @Test
    fun `encrypted-only reasoning renders the notice without text`() {
        val display = assertNotNull(
            assistantMessage(listOf(reasoningItem(encryptedContent = "opaque"))).deriveReasoningDisplay()
        )

        assertTrue(display.showsEncryptedNotice)
        assertEquals("", display.reasoningText)
        assertEquals("", display.summaryText)
    }

    @Test
    fun `encrypted reasoning with a summary renders both the summary and the notice`() {
        val display = assertNotNull(
            assistantMessage(
                listOf(reasoningItem(summaryText = listOf("Summary of encrypted reasoning"), encryptedContent = "opaque"))
            ).deriveReasoningDisplay()
        )

        assertEquals("Summary of encrypted reasoning", display.summaryText)
        assertTrue(display.showsEncryptedNotice)
    }

    @Test
    fun `encrypted reasoning next to plaintext reasoning suppresses the notice`() {
        val display = assertNotNull(
            assistantMessage(
                listOf(
                    reasoningItem(encryptedContent = "opaque"),
                    reasoningItem(contentText = listOf("Visible chain of thought"))
                )
            ).deriveReasoningDisplay()
        )

        assertEquals("Visible chain of thought", display.reasoningText)
        // The notice must never contradict reasoning that is rendered right below it.
        assertFalse(display.showsEncryptedNotice)
    }

    @Test
    fun `an item with nothing to show renders nothing`() {
        val nullEncryptedContent = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("encrypted_content", JsonNull)
        }
        val metadataOnly = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray { })
        }

        // Neither an empty summary nor a null opaque payload is reasoning to display.
        assertNull(assistantMessage(listOf(nullEncryptedContent)).deriveReasoningDisplay())
        assertNull(assistantMessage(listOf(metadataOnly)).deriveReasoningDisplay())
    }

    @Test
    fun `items and parts are rendered in order with a blank line between items`() {
        val display = assertNotNull(
            assistantMessage(
                listOf(
                    reasoningItem(summaryText = listOf("First summary"), contentText = listOf("part one, ", "part two")),
                    reasoningItem(summaryText = listOf("Second summary"), contentText = listOf("second thought"))
                )
            ).deriveReasoningDisplay()
        )

        assertEquals("First summary\n\nSecond summary", display.summaryText)
        assertEquals("part one, part two\n\nsecond thought", display.reasoningText)
    }

    @Test
    fun `non-plaintext fields of an item never reach the display`() {
        val item = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_secret_id"))
            put("status", JsonPrimitive("completed"))
            put("encrypted_content", JsonPrimitive("opaque-secret"))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("reasoning_text"))
                            put("text", JsonPrimitive("visible"))
                            put("format", JsonPrimitive("raw"))
                        }
                    )
                }
            )
        }

        val display = assertNotNull(assistantMessage(listOf(item)).deriveReasoningDisplay())

        assertEquals("visible", display.reasoningText)
        // Neither the opaque payload nor the raw item JSON is part of the display model.
        assertFalse(display.toString().contains("opaque-secret"))
        assertFalse(display.toString().contains("rs_secret_id"))
    }

    @Test
    fun `text longer than the render cap is cut and reported as truncated`() {
        val oversized = "r".repeat(MAX_RENDERED_REASONING_CHARS + 10)

        val display = assertNotNull(
            assistantMessage(listOf(reasoningItem(contentText = listOf(oversized)))).deriveReasoningDisplay()
        )

        assertEquals(MAX_RENDERED_REASONING_CHARS, display.reasoningText.length)
        assertEquals(oversized.take(MAX_RENDERED_REASONING_CHARS), display.reasoningText)
        assertTrue(display.isTruncated)
    }

    @Test
    fun `text at the render cap is not truncated`() {
        val exact = "s".repeat(MAX_RENDERED_REASONING_CHARS)

        val display = assertNotNull(
            assistantMessage(listOf(reasoningItem(contentText = listOf(exact)))).deriveReasoningDisplay()
        )

        assertEquals(exact, display.reasoningText)
        assertFalse(display.isTruncated)
    }

    @Test
    fun `an over-cap summary alone marks the display as truncated`() {
        val display = assertNotNull(
            assistantMessage(
                listOf(reasoningItem(summaryText = listOf("x".repeat(MAX_RENDERED_REASONING_CHARS + 1))))
            ).deriveReasoningDisplay()
        )

        assertTrue(display.isTruncated)
        assertEquals(MAX_RENDERED_REASONING_CHARS, display.summaryText.length)
    }

    @Test
    fun `first delta creates the streamed-text item`() {
        val items = (null as List<JsonObject>?).appendReasoningTextDelta("first ")

        val content = items.single()["content"] as JsonArray
        assertEquals("first ", (content.single() as JsonObject)["text"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `later deltas extend the trailing streamed-text item`() {
        val items = (null as List<JsonObject>?)
            .appendReasoningTextDelta("first ")
            .appendReasoningTextDelta("second")

        assertEquals(1, items.size)
        val content = items.single()["content"] as JsonArray
        assertEquals("first second", (content.single() as JsonObject)["text"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `a delta is appended to the trailing streamed item of an existing list`() {
        val persisted = listOf(reasoningItem(encryptedContent = "opaque"))

        val items = persisted.appendReasoningTextDelta("live ").appendReasoningTextDelta("text")

        assertEquals(2, items.size)
        assertEquals(persisted.single(), items.first())
        val content = items.last()["content"] as JsonArray
        assertEquals("live text", (content.single() as JsonObject)["text"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `a blank delta leaves the items unchanged`() {
        val items = listOf(reasoningItem(contentText = listOf("existing")))

        assertEquals(items, items.appendReasoningTextDelta(""))
        // Blank, not merely empty: the server drops whitespace-only text too, so both sides agree on what counts
        // as reasoning, and nothing is created for a stream that only produced whitespace.
        assertEquals(items, items.appendReasoningTextDelta("   "))
        assertEquals(emptyList(), (null as List<JsonObject>?).appendReasoningTextDelta("\n"))
    }

    @Test
    fun `an encrypted trailing item is never extended by a delta`() {
        // The streamed item carries no opaque payload, so an encrypted item is never mistaken for the live
        // placeholder even when it sits at the end of the list.
        val encrypted = reasoningItem(encryptedContent = "opaque")

        val items = listOf(encrypted).appendReasoningTextDelta("live")

        assertEquals(2, items.size)
        assertEquals(encrypted, items.first())
    }

    @Test
    fun `a trailing item with an explicit null encrypted payload is extended as plaintext`() {
        // Providers can emit `"encrypted_content": null` and the persisted shape keeps it. Both this append rule and
        // the display rule must read that as "no opaque payload", otherwise the next delta would start a second item
        // and the rendered reasoning would gain a spurious blank-line separator.
        val providerItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("encrypted_content", JsonNull)
            put("content", JsonArray(listOf(textPart("reasoning_text", "first"))))
        }

        val items = listOf(providerItem).appendReasoningTextDelta(" second")

        assertEquals(1, items.size)
        val content = items.single()["content"] as JsonArray
        assertEquals("first second", (content.single() as JsonObject)["text"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `streamed deltas render from the cache without a completing event`() {
        val items = (null as List<JsonObject>?)
            .appendReasoningTextDelta("think")
            .appendReasoningTextDelta("ing")

        val display = assertNotNull(assistantMessage(items).deriveReasoningDisplay())

        assertEquals("thinking", display.reasoningText)
    }
}
