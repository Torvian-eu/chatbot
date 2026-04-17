package eu.torvian.chatbot.server.worker.command.pending

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the in-memory pending command registry completes, removes, and disconnect-fails entries.
 */
class PendingWorkerCommandRegistryTest {
    @Test
    fun `register and complete removes pending command`() = runTest {
        val registry = InMemoryPendingWorkerCommandRegistry()
        val completion = CompletableDeferred<WorkerCommandDispatchResult>()
        val pending = PendingWorkerCommand(
            workerId = 42L,
            interactionId = "interaction-1",
            messageId = "message-1",
            commandType = "tool.call",
            completion = completion
        )
        val outcome = WorkerCommandDispatchResult.Completed(
            workerId = 42L,
            interactionId = "interaction-1",
            commandType = "tool.call",
            result = WorkerCommandResultPayload(
                status = "success",
                data = buildJsonObject { put("value", "ok") }
            )
        )

        assertTrue(registry.register(pending))
        assertEquals(pending, registry.get("interaction-1"))
        assertTrue(registry.complete("interaction-1", outcome))

        assertTrue(completion.isCompleted)
        assertEquals(outcome, completion.await())
        assertNull(registry.get("interaction-1"))
    }

    @Test
    fun `disconnect completes all commands for a worker`() = runTest {
        val registry = InMemoryPendingWorkerCommandRegistry()
        val completionOne = CompletableDeferred<WorkerCommandDispatchResult>()
        val completionTwo = CompletableDeferred<WorkerCommandDispatchResult>()
        val pendingOne = PendingWorkerCommand(
            workerId = 7L,
            interactionId = "interaction-a",
            messageId = "message-a",
            commandType = "tool.call",
            completion = completionOne
        )
        val pendingTwo = PendingWorkerCommand(
            workerId = 7L,
            interactionId = "interaction-b",
            messageId = "message-b",
            commandType = "mcp.tool.call",
            completion = completionTwo
        )

        assertTrue(registry.register(pendingOne))
        assertTrue(registry.register(pendingTwo))

        val completedCount = registry.failAllForWorker(7L, "Worker session disconnected")

        assertEquals(2, completedCount)
        assertTrue(completionOne.isCompleted)
        assertTrue(completionTwo.isCompleted)
        assertEquals(
            WorkerCommandDispatchResult.SessionDisconnected(
                workerId = 7L,
                interactionId = "interaction-a",
                commandType = "tool.call",
                reason = "Worker session disconnected"
            ),
            completionOne.await()
        )
        assertEquals(
            WorkerCommandDispatchResult.SessionDisconnected(
                workerId = 7L,
                interactionId = "interaction-b",
                commandType = "mcp.tool.call",
                reason = "Worker session disconnected"
            ),
            completionTwo.await()
        )
        assertNull(registry.get("interaction-a"))
        assertNull(registry.get("interaction-b"))
        assertFalse(registry.remove("interaction-a"))
    }
}


