package eu.torvian.chatbot.server.ktor.websocket.session

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationManualCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.ManualCompactionOutcome
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the compaction socket's frame contract: exactly one terminal outcome frame followed by the
 * terminal marker and a normal close, for a persisted chunk, a skip, a failure, an access denial and an
 * unexpected server error; and a peer that left mid-write leaving the handler quiet. The abort of a
 * compaction whose peer closed the socket is covered by
 * [SessionCompactionWebSocketCancellationTest].
 */
class SessionCompactionWebSocketHandlerTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val userId = 1L
    private val sessionId = 7L

    private val manualService = mockk<ConversationManualCompactionService>()
    private val authorizationService = mockk<AuthorizationService>()

    private val handler = SessionCompactionWebSocketHandler(
        manualCompactionService = manualService,
        authorizationService = authorizationService,
        json = json
    )

    /** Persisted chunk the completed outcome reports. */
    private val chunk = ConversationCompactionChunk(
        id = 42L,
        sessionId = sessionId,
        summary = "A concise summary of the conversation.",
        modelId = 1L,
        settingsId = 2L,
        providerId = 3L,
        modelName = "Model Name",
        settingsName = "Settings Name",
        providerName = "Provider Name",
        instruction = "Summarize faithfully",
        thresholdTokens = 100_000L,
        sourceTokenCount = 4_500L,
        resultTokenCount = 2_000L,
        tokenCounterVersion = "test-v1",
        coverageCount = 1,
        createdAt = 1_700_000_000_100L,
        coverage = listOf(CompactedMessageCoverage(ordinal = 0, messageId = 10L, observedUpdatedAt = t))
    )

    /**
     * Builds a relaxed socket mock whose outbound frames land in the returned channel.
     *
     * The mock is relaxed because the handler closes the socket through Ktor's `close` extension, which
     * touches unrelated session members the test does not care about. The inbound channel is a real,
     * never-closing one: these cases are about the frame contract, and a peer that stays connected must
     * leave the compaction running to its outcome.
     *
     * @return The socket and the channel collecting the frames it sent.
     */
    private fun socket(): Pair<DefaultWebSocketServerSession, Channel<Frame>> {
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        val socket = mockk<DefaultWebSocketServerSession>(relaxed = true)
        every { socket.outgoing } returns outgoing
        every { socket.incoming } returns Channel<Frame>(Channel.UNLIMITED)
        return socket to outgoing
    }

    /**
     * Drains the text frames a socket sent, in order, ignoring any other frame kind.
     *
     * @param outgoing Channel the socket wrote to.
     * @return The decoded text-frame payloads.
     */
    private fun drain(outgoing: Channel<Frame>): List<String> {
        val payloads = mutableListOf<String>()
        while (true) {
            val frame = outgoing.tryReceive().getOrNull() ?: break
            (frame as? Frame.Text)?.let { textFrame -> payloads.add(textFrame.readText()) }
        }
        return payloads
    }

    /** Grants session access for every case that is not about authorization. */
    private fun grantAccess() {
        coEvery { authorizationService.requireAccess(any(), any(), any(), any()) } returns Unit.right()
    }

    @Test
    fun `a persisted outcome sends the completed event then the terminal marker and closes`() = runTest {
        grantAccess()
        coEvery { manualService.compactThread(userId, sessionId) } returns
            ManualCompactionOutcome.Persisted(chunk).right()
        val (socket, outgoing) = socket()

        handler.handle(socket, userId, sessionId)

        val payloads = drain(outgoing)
        assertEquals(2, payloads.size)
        val outcome = json.decodeFromString(CompactionEvent.serializer(), payloads[0])
        assertEquals(42L, assertIs<CompactionEvent.Completed>(outcome).payload.chunkId)
        assertEquals(
            CompactionEvent.StreamCompleted,
            json.decodeFromString(CompactionEvent.serializer(), payloads[1])
        )
    }

    @Test
    fun `a skipped outcome sends the skip reason then the terminal marker`() = runTest {
        grantAccess()
        coEvery { manualService.compactThread(userId, sessionId) } returns
            ManualCompactionOutcome.Skipped(CompactionSkipReason.NOTHING_TO_COMPACT).right()
        val (socket, outgoing) = socket()

        handler.handle(socket, userId, sessionId)

        val payloads = drain(outgoing)
        assertEquals(2, payloads.size)
        val outcome = json.decodeFromString(CompactionEvent.serializer(), payloads[0])
        assertEquals(CompactionSkipReason.NOTHING_TO_COMPACT, assertIs<CompactionEvent.Skipped>(outcome).reason)
        assertEquals(
            CompactionEvent.StreamCompleted,
            json.decodeFromString(CompactionEvent.serializer(), payloads[1])
        )
    }

    @Test
    fun `a failure sends the mapped error then the terminal marker`() = runTest {
        grantAccess()
        coEvery { manualService.compactThread(userId, sessionId) } returns
            ConversationCompactionError.InsufficientReduction(4_500L, 2_000L, 1_000L).left()
        val (socket, outgoing) = socket()

        handler.handle(socket, userId, sessionId)

        val payloads = drain(outgoing)
        assertEquals(2, payloads.size)
        val outcome = json.decodeFromString(CompactionEvent.serializer(), payloads[0])
        assertEquals(
            "conversation-compaction-failed",
            assertIs<CompactionEvent.ErrorOccurred>(outcome).error.code
        )
        assertEquals(
            CompactionEvent.StreamCompleted,
            json.decodeFromString(CompactionEvent.serializer(), payloads[1])
        )
    }

    @Test
    fun `an access failure is answered with an error frame and the terminal marker`() = runTest {
        coEvery { authorizationService.requireAccess(userId, ResourceType.SESSION, sessionId, AccessMode.WRITE) } returns
            ResourceAuthorizationError.AccessDenied(userId, ResourceType.SESSION, sessionId, AccessMode.WRITE).left()
        val (socket, outgoing) = socket()

        handler.handle(socket, userId, sessionId)

        val payloads = drain(outgoing)
        assertEquals(2, payloads.size)
        val outcome = json.decodeFromString(CompactionEvent.serializer(), payloads[0])
        assertEquals(
            "permission-denied",
            assertIs<CompactionEvent.ErrorOccurred>(outcome).error.code
        )
        assertEquals(
            CompactionEvent.StreamCompleted,
            json.decodeFromString(CompactionEvent.serializer(), payloads[1])
        )
        coVerify(exactly = 0) { manualService.compactThread(any(), any()) }
    }

    @Test
    fun `a peer that left mid-write does not fail the handler`() = runTest {
        grantAccess()
        coEvery { manualService.compactThread(userId, sessionId) } returns
            ManualCompactionOutcome.Persisted(chunk).right()
        val (socket, outgoing) = socket()
        // The peer is already gone: every write fails, which the handler must absorb.
        outgoing.close()

        handler.handle(socket, userId, sessionId)

        assertTrue(drain(outgoing).isEmpty())
    }

    @Test
    fun `an unexpected failure sends an internal error frame then the terminal marker`() = runTest {
        grantAccess()
        coEvery { manualService.compactThread(userId, sessionId) } throws IllegalStateException("boom")
        val (socket, outgoing) = socket()

        handler.handle(socket, userId, sessionId)

        val payloads = drain(outgoing)
        assertEquals(2, payloads.size)
        val outcome = json.decodeFromString(CompactionEvent.serializer(), payloads[0])
        val error = assertIs<CompactionEvent.ErrorOccurred>(outcome).error
        assertEquals(CommonApiErrorCodes.INTERNAL.statusCode, error.statusCode)
        assertEquals(
            CompactionEvent.StreamCompleted,
            json.decodeFromString(CompactionEvent.serializer(), payloads[1])
        )
    }
}
