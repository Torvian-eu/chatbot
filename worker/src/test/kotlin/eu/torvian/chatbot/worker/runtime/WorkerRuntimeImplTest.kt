package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.protocol.transport.WorkerTransportConnectionLoopRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerRuntimeImplTest {

    @Test
    fun `runtime delegates runOnce flag to connection loop`() = runTest {
        val loop = RecordingConnectionLoop(Either.Right(Unit))
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            connectionLoop = loop
        )

        val result = runtime.run(runOnce = true)

        assertTrue(result.isRight())
        assertEquals(1, loop.calls)
        assertEquals(true, loop.lastRunOnce)
    }

    @Test
    fun `runtime propagates auth errors returned by connection loop`() = runTest {
        val expectedError = WorkerAuthManagerError.BlankChallengePayload
        val loop = RecordingConnectionLoop(Either.Left(expectedError))
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            connectionLoop = loop
        )

        val result = runtime.run(runOnce = false)

        assertTrue(result.isLeft())
        assertEquals(expectedError, result.swap().getOrNull())
        assertEquals(false, loop.lastRunOnce)
    }

    private class RecordingConnectionLoop(
        private val result: Either<WorkerAuthManagerError, Unit>
    ) : WorkerTransportConnectionLoopRunner {
        var calls: Int = 0
        var lastRunOnce: Boolean? = null

        override suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit> {
            calls += 1
            lastRunOnce = runOnce
            return result
        }
    }
}


