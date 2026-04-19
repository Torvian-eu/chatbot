package eu.torvian.chatbot.server.worker.protocol.routing

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchSuccess
import eu.torvian.chatbot.server.worker.command.pending.InMemoryPendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommand
import eu.torvian.chatbot.server.worker.protocol.handshake.WorkerSessionHelloHandler
import eu.torvian.chatbot.server.worker.session.ConnectedWorkerSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that the inbound worker router correlates command lifecycle frames to pending dispatches.
 */
class WorkerServerIncomingMessageRouterTest {
    private val helloHandler = mockk<WorkerSessionHelloHandler>(relaxed = true)
    private val pendingRegistry = InMemoryPendingWorkerCommandRegistry()
    private val router = WorkerServerIncomingMessageRouter(helloHandler, pendingRegistry)
    private val workerContext = WorkerContext(
        workerId = 99L,
        workerUid = "worker-99",
        ownerUserId = 7L,
        scopes = listOf("messages:read"),
        tokenIssuedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        tokenExpiresAt = Instant.fromEpochMilliseconds(1_700_003_600_000)
    )
    private val session = mockk<ConnectedWorkerSession>()

    @BeforeEach
    fun setUp() {
        every { session.isReady() } returns true
        every { session.workerContext } returns workerContext
    }

    @Test
    fun `command accepted keeps pending interaction active`() = runTest {
        val completion = CompletableDeferred<Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess>>()
        val pending = PendingWorkerCommand(
            workerId = workerContext.workerId,
            interactionId = "interaction-accepted",
            messageId = "message-accepted",
            commandType = "tool.call",
            completion = completion
        )
        assertTrue(pendingRegistry.register(pending))

        router.route(
            session,
            commandAccepted(
                id = "accepted-message",
                replyTo = "message-accepted",
                interactionId = pending.interactionId,
                payload = WorkerCommandAcceptedPayload
            )
        )

        assertNotNull(pendingRegistry.get(pending.interactionId))
        assertFalse(completion.isCompleted)
    }

    @Test
    fun `command result completes pending interaction`() = runTest {
        val completion = CompletableDeferred<Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess>>()
        val pending = PendingWorkerCommand(
            workerId = workerContext.workerId,
            interactionId = "interaction-result",
            messageId = "message-result",
            commandType = "tool.call",
            completion = completion
        )
        assertTrue(pendingRegistry.register(pending))

        val payload = WorkerCommandResultPayload(
            status = "success",
            data = buildJsonObject { put("answer", 42) }
        )
        router.route(
            session,
            commandResult(
                id = "result-message",
                replyTo = "message-result",
                interactionId = pending.interactionId,
                payload = payload
            )
        )

        assertNull(pendingRegistry.get(pending.interactionId))
        assertEquals(
            WorkerCommandDispatchSuccess(
                workerId = workerContext.workerId,
                interactionId = pending.interactionId,
                commandType = pending.commandType,
                result = payload
            ).right(),
            completion.await()
        )
    }

    @Test
    fun `command rejected completes pending interaction`() = runTest {
        val completion = CompletableDeferred<Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess>>()
        val pending = PendingWorkerCommand(
            workerId = workerContext.workerId,
            interactionId = "interaction-rejected",
            messageId = "message-rejected",
            commandType = "mcp.tool.call",
            completion = completion
        )
        assertTrue(pendingRegistry.register(pending))

        val payload = WorkerCommandRejectedPayload(
            commandType = pending.commandType,
            reasonCode = "unsupported_command_type",
            message = "Command not available on this worker"
        )
        router.route(
            session,
            commandRejected(
                id = "rejected-message",
                replyTo = "message-rejected",
                interactionId = pending.interactionId,
                payload = payload
            )
        )

        assertNull(pendingRegistry.get(pending.interactionId))
        assertEquals(
            WorkerCommandDispatchError.Rejected(
                workerId = workerContext.workerId,
                interactionId = pending.interactionId,
                commandType = pending.commandType,
                rejection = payload
            ).left(),
            completion.await()
        )
    }

    @Test
    fun `malformed command result completes pending interaction with malformed outcome`() = runTest {
        val completion = CompletableDeferred<Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess>>()
        val pending = PendingWorkerCommand(
            workerId = workerContext.workerId,
            interactionId = "interaction-malformed",
            messageId = "message-malformed",
            commandType = "tool.call",
            completion = completion
        )
        assertTrue(pendingRegistry.register(pending))

        val malformedMessage = WorkerProtocolMessage(
            id = "malformed-message",
            type = WorkerProtocolMessageTypes.COMMAND_RESULT,
            interactionId = pending.interactionId,
            payload = buildJsonObject { put("status", "success") }
        )
        router.route(session, malformedMessage)

        assertNull(pendingRegistry.get(pending.interactionId))
        val outcome = completion.await().fold(
            ifLeft = { it },
            ifRight = { error("Expected malformed lifecycle error but got completion: $it") }
        )
        assertTrue(outcome is WorkerCommandDispatchError.MalformedLifecyclePayload)
        assertEquals(pending.interactionId, outcome.interactionId)
        assertEquals(pending.commandType, outcome.commandType)
    }
}



