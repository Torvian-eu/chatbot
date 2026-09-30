package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the streamed reasoning-text wire contract of [ChatStreamEvent.AssistantMessageReasoningDelta].
 *
 * The subtype is a pure addition, so its discriminator must be pinned (an older peer can then recognize the event
 * by name rather than by position) and its payload must round-trip through the shared serializer.
 */
class AssistantMessageReasoningDeltaSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `uses the pinned event type`() {
        val event = ChatStreamEvent.AssistantMessageReasoningDelta(messageId = 3L, deltaContent = "why")

        assertEquals("assistant_message_reasoning_delta", event.eventType)
    }

    @Test
    fun `serializes and deserializes through the sealed hierarchy`() {
        val event = ChatStreamEvent.AssistantMessageReasoningDelta(messageId = 11L, deltaContent = "a thought")

        val wire = json.encodeToString(ChatStreamEvent.serializer(), event)

        assertTrue(wire.contains("\"eventType\":\"assistant_message_reasoning_delta\""))
        assertTrue(wire.contains("\"messageId\":11"))
        assertTrue(wire.contains("\"deltaContent\":\"a thought\""))

        val decoded = json.decodeFromString(ChatStreamEvent.serializer(), wire)

        assertEquals(event, assertIs<ChatStreamEvent.AssistantMessageReasoningDelta>(decoded))
        assertEquals("assistant_message_reasoning_delta", decoded.eventType)
    }

    @Test
    fun `carries only the message id and the plaintext delta`() {
        // The subtype deliberately exposes no opaque reasoning payload: the completed items travel with the
        // end-of-message event instead.
        val event = ChatStreamEvent.AssistantMessageReasoningDelta(messageId = 12L, deltaContent = "text")

        assertEquals(setOf("type", "messageId", "deltaContent", "eventType"), serializedKeys(event))
    }

    /**
     * Lists the JSON keys the event serializes to.
     *
     * @param event Event to serialize.
     * @return The keys of the serialized object.
     */
    private fun serializedKeys(event: ChatStreamEvent): Set<String> =
        json.parseToJsonElement(json.encodeToString(ChatStreamEvent.serializer(), event))
            .let { it as JsonObject }
            .keys
}
