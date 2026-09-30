package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the public conversion of the streamed reasoning-text event and its wire contract.
 *
 * The event is a streaming-only addition, so the mapping must preserve the correlation id and the delta verbatim,
 * and the wire tag must be pinned so an older or newer peer can recognize it by name.
 */
class ChatStreamEventReasoningDeltaMapperTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `message stream event maps to the chat stream reasoning delta`() {
        val streamEvent = MessageStreamEvent.AssistantMessageReasoningDelta(
            messageId = 42L,
            deltaContent = "thinking"
        )

        val mapped = assertIs<ChatStreamEvent.AssistantMessageReasoningDelta>(streamEvent.toChatStreamEvent())

        assertEquals(42L, mapped.messageId)
        assertEquals("thinking", mapped.deltaContent)
        assertEquals("assistant_message_reasoning_delta", mapped.eventType)
    }

    @Test
    fun `serialized reasoning delta carries the pinned event type and round-trips`() {
        val event = ChatStreamEvent.AssistantMessageReasoningDelta(
            messageId = 7L,
            deltaContent = "a thought"
        )

        val wire = json.encodeToString(ChatStreamEvent.serializer(), event)

        assertTrue(wire.contains("\"eventType\":\"assistant_message_reasoning_delta\""))
        assertTrue(wire.contains("\"messageId\":7"))
        assertTrue(wire.contains("\"deltaContent\":\"a thought\""))

        val decoded = json.decodeFromString(ChatStreamEvent.serializer(), wire)

        assertEquals(event, assertIs<ChatStreamEvent.AssistantMessageReasoningDelta>(decoded))
    }
}
