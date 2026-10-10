package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.right
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonWebSocketProtocols
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Verifies the compaction socket transport over a real WebSocket handshake.
 *
 * The route is served by a test-host application and the client under test is the production
 * [KtorChatApiClient], so the requested path, the offered subprotocol and the decoding of every
 * [CompactionEvent] variant are asserted end to end rather than by codec round-trip.
 */
class KtorChatApiClientCompactionSocketTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val sessionId = 123L

    /** Every terminal variant plus the terminal marker, in the order the server sends them. */
    private val serverEvents: List<CompactionEvent> = listOf(
        CompactionEvent.Completed(
            CompactionCompletedPayload(
                chunkId = 42L,
                sessionId = sessionId,
                coveredMessageIds = listOf(1L, 2L),
                modelId = 1L,
                settingsId = 2L,
                providerId = 3L,
                modelName = "Model",
                settingsName = "Settings",
                providerName = "Provider",
                sourceTokenCount = 4_500L,
                resultTokenCount = 2_000L,
                summaryPreview = "A concise summary.",
                createdAt = 1_700_000_000_100L
            )
        ),
        CompactionEvent.Skipped(CompactionSkipReason.ALREADY_COMPACTED),
        CompactionEvent.ErrorOccurred(
            apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, "Compaction failed")
        ),
        CompactionEvent.StreamCompleted
    )

    @Test
    fun `compactConversation connects to the dedicated resource and decodes every event variant`() =
        testApplication {
            val requestedPaths = mutableListOf<String>()
            val offeredSubprotocols = mutableListOf<String?>()

            application {
                install(ServerWebSockets)
            }
            routing {
                webSocket("/api/v1/sessions/{sessionId}/compaction") {
                    requestedPaths.add(call.request.path())
                    offeredSubprotocols.add(call.request.headers[HttpHeaders.SecWebSocketProtocol])
                    // The server speaks first: connecting is the request, so no client frame is awaited.
                    serverEvents.forEach { event ->
                        send(Frame.Text(json.encodeToString(CompactionEvent.serializer(), event)))
                    }
                    close(CloseReason(CloseReason.Codes.NORMAL, "Compaction finished"))
                }
            }

            val apiClient = KtorChatApiClient(createClient { install(WebSockets) })

            val received = apiClient.compactConversation(sessionId).toList()

            assertEquals(listOf("/api/v1/sessions/$sessionId/compaction"), requestedPaths)
            assertTrue(
                offeredSubprotocols.single().orEmpty().contains(CommonWebSocketProtocols.CHATBOT_AUTH),
                "The socket must offer the authentication subprotocol marker: ${offeredSubprotocols.single()}"
            )
            assertEquals(
                serverEvents.map { event -> event.right() },
                received,
                "Every variant must decode to its own type, in order"
            )
        }
}
