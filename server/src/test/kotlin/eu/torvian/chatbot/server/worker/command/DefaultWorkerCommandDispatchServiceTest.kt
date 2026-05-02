package eu.torvian.chatbot.server.worker.command

import arrow.core.getOrElse
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.worker.command.pending.InMemoryPendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.session.ConnectedWorkerSession
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that the outbound dispatcher writes request frames, waits for completion, and times out.
 */
class DefaultWorkerCommandDispatchServiceTest {
    private val workerSessionRegistry = mockk<WorkerSessionRegistry>()
    private val pendingCommandRegistry = InMemoryPendingWorkerCommandRegistry()
    private val session = mockk<ConnectedWorkerSession>()
    private val workerContext = WorkerContext(
        workerId = 101L,
        workerUid = "worker-101",
        ownerUserId = 7L,
        scopes = listOf("messages:read"),
        tokenIssuedAt = Clock.System.now(),
        tokenExpiresAt = Clock.System.now()
    )
    private val service = DefaultWorkerCommandDispatchService(
        workerSessionRegistry = workerSessionRegistry,
        pendingCommandRegistry = pendingCommandRegistry,
        defaultTimeout = 30.seconds
    )

    @BeforeEach
    fun setUp() {
        every { session.workerContext } returns workerContext
        every { session.isReady() } returns true
        every { workerSessionRegistry.get(workerContext.workerId) } returns session
    }

    @Test
    fun `dispatch completes after the worker returns a result`() = runTest {
        coEvery { session.send(any()) } answers {
            val outbound = firstArg<WorkerProtocolMessage>()
            assertEquals(WorkerProtocolMessageTypes.COMMAND_REQUEST, outbound.type)
            val pending = pendingCommandRegistry.get(outbound.interactionId)
            requireNotNull(pending)
            val outcome = WorkerCommandDispatchSuccess(
                workerId = workerContext.workerId,
                interactionId = outbound.interactionId,
                commandType = pending.commandType,
                result = WorkerCommandResultPayload(
                    status = "success",
                    data = buildJsonObject { put("value", "ok") }
                )
            ).right()
            pendingCommandRegistry.complete(
                interactionId = outbound.interactionId,
                outcome = outcome
            )
            true
        }

        val result = service.dispatch(
            workerId = workerContext.workerId,
            commandRequestPayload = WorkerCommandRequestPayload(
                commandType = "tool.call",
                data = buildJsonObject { put("tool", "demo") }
            )
        )

        val completed = result.getOrElse { error("Expected completed dispatch result: $it") }
        assertEquals(workerContext.workerId, completed.workerId)
        assertEquals("tool.call", completed.commandType)
        assertTrue(completed.result.status == "success")
        assertNull(pendingCommandRegistry.get(completed.interactionId))
    }

    @Test
    fun `dispatch times out when no terminal lifecycle frame arrives`() = runTest {
        coEvery { session.send(any()) } returns true

        val result = service.dispatch(
            workerId = workerContext.workerId,
            commandRequestPayload = WorkerCommandRequestPayload(
                commandType = "tool.call",
                data = buildJsonObject { put("tool", "slow") }
            ),
            timeout = kotlin.time.Duration.ZERO
        )

        val timeout = result.fold(
            ifLeft = { it },
            ifRight = { error("Expected timeout dispatch error but got completion: $it") }
        )
        assertIs<WorkerCommandDispatchError.TimedOut>(timeout)
        assertEquals(workerContext.workerId, timeout.workerId)
        assertEquals("tool.call", timeout.commandType)
        assertNull(pendingCommandRegistry.get(timeout.interactionId))
    }

    @Test
    fun `dispatch reports worker not connected when no live session is available`() = runTest {
        every { workerSessionRegistry.get(workerContext.workerId) } returns null

        val result = service.dispatch(
            workerId = workerContext.workerId,
            commandRequestPayload = WorkerCommandRequestPayload(
                commandType = "tool.call",
                data = buildJsonObject { put("tool", "missing") }
            )
        )

        assertEquals(
            WorkerCommandDispatchError.WorkerNotConnected(workerContext.workerId),
            result.fold(ifLeft = { it }, ifRight = { error("Expected not-connected error but got completion: $it") })
        )
    }
}



