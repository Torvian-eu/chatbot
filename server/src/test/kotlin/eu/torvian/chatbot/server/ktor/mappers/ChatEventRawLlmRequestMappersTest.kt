package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.server.service.core.MessageEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies that the captured provider request maps unchanged through the public conversion path and
 * that the serialized wire shape carries the pinned event type and payload fields.
 */
class ChatEventRawLlmRequestMappersTest {

    private val payload = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer sk-abcde..."),
        body = """{"model":"gpt-4"}"""
    )

    @Test
    fun `non-streaming service event maps to the chat event carrying the same payload`() {
        val mapped = MessageEvent.RawLlmRequest(payload).toChatEvent()

        val event = assertIs<ChatEvent.RawLlmRequest>(mapped)
        assertEquals(payload, event.payload)
        assertEquals("raw_llm_request", event.eventType)
    }

    @Test
    fun `streaming service event maps to the chat stream event carrying the same payload`() {
        val mapped = MessageStreamEvent.RawLlmRequest(payload).toChatStreamEvent()

        val event = assertIs<ChatStreamEvent.RawLlmRequest>(mapped)
        assertEquals(payload, event.payload)
        assertEquals("raw_llm_request", event.eventType)
    }

    @Test
    fun `the wire shape round-trips method, url, headers and body`() {
        val json = Json.encodeToString(payload)

        assertEquals(payload, Json.decodeFromString<RawLlmRequestPayload>(json))
    }
}
