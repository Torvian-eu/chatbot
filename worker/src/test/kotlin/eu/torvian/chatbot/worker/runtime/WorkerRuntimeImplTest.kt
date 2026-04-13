package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.StoredServiceToken
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerRuntimeImplTest {

    @Test
    fun `runOnce authenticates once and returns successfully`() = runTest {
        val manager = RecordingAuthManager(
            responses = mutableListOf(Either.Right(tokenAt("2026-04-10T12:00:00Z")))
        )
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            refreshSkew = 60.seconds,
            authManager = manager,
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = runtime.run(runOnce = true)

        assertTrue(result.isRight())
        assertEquals(1, manager.callCount)
    }

    @Test
    fun `non runOnce mode performs refresh check cycle after delay`() = runTest {
        val manager = RecordingAuthManager(
            responses = mutableListOf(
                Either.Right(tokenAt("2026-04-10T11:05:00Z")),
                Either.Right(tokenAt("2026-04-10T11:05:00Z")),
                Either.Right(tokenAt("2026-04-10T11:10:00Z"))
            )
        )
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            refreshSkew = 60.seconds,
            authManager = manager,
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val finishedCycle = CompletableDeferred<Unit>()
        manager.onCall = {
            if (manager.callCount >= 3 && !finishedCycle.isCompleted) {
                finishedCycle.complete(Unit)
            }
        }

        val job = async { runtime.run(runOnce = false) }

        advanceTimeBy(5_000)
        finishedCycle.await()

        job.cancelAndJoin()

        assertTrue(manager.callCount >= 3)
    }

    @Test
    fun `cancellation in non runOnce mode is handled gracefully`() = runTest {
        val manager = RecordingAuthManager(
            responses = mutableListOf(
                Either.Right(tokenAt("2026-04-10T11:10:00Z")),
                Either.Right(tokenAt("2026-04-10T11:10:00Z"))
            )
        )
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            refreshSkew = 60.seconds,
            authManager = manager,
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val job = async { runtime.run(runOnce = false) }
        advanceTimeBy(1_000)
        job.cancelAndJoin()

        assertTrue(manager.callCount >= 2)
    }

    @Test
    fun `auth failure is propagated`() = runTest {
        val manager = RecordingAuthManager(
            responses = mutableListOf(Either.Left(WorkerAuthManagerError.BlankChallengePayload))
        )
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            refreshSkew = 60.seconds,
            authManager = manager,
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = runtime.run(runOnce = true)

        assertTrue(result.isLeft())
        assertEquals(WorkerAuthManagerError.BlankChallengePayload, result.swap().getOrNull())
    }

    private class RecordingAuthManager(
        private val responses: MutableList<Either<WorkerAuthManagerError, StoredServiceToken>>
    ) : WorkerAuthManager {
        var callCount: Int = 0
        var onCall: (() -> Unit)? = null

        override suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> {
            callCount++
            onCall?.invoke()
            return responses.removeFirstOrNull() ?: Either.Right(tokenAt("2026-04-10T11:20:00Z"))
        }

        override suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> {
            return responses.removeFirstOrNull() ?: Either.Right(tokenAt("2026-04-10T11:20:00Z"))
        }
    }

    private companion object {
        fun tokenAt(expiresAt: String): StoredServiceToken {
            return StoredServiceToken(
                accessToken = "token-$expiresAt",
                expiresAt = Instant.parse(expiresAt)
            )
        }
    }
}


