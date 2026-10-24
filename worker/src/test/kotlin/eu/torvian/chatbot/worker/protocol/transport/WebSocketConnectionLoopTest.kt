package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.AssignedCredentialsDto
import eu.torvian.chatbot.worker.auth.StoredServiceToken
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.credential.InMemoryWorkerCredentialStore
import eu.torvian.chatbot.worker.credential.api.AssignedCredentialBootstrapError
import eu.torvian.chatbot.worker.credential.api.AssignedCredentialBootstrapper
import eu.torvian.chatbot.worker.credential.api.WorkerCredentialApi
import eu.torvian.chatbot.worker.credential.api.WorkerCredentialApiError
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapError
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapper
import eu.torvian.chatbot.worker.mcp.api.WorkerMcpServerApiError
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Unit tests for [WebSocketConnectionLoop] bootstrap and runtime orchestration.
 *
 * Verifies that the connection loop correctly handles bootstrap failures, success,
 * token acquisition ordering, and runOnce behavior.
 */
class WebSocketConnectionLoopTest {

    /**
     * Verifies that bootstrap failure in runOnce mode returns AssignedConfigBootstrap error.
     *
     * Given a bootstrapper that fails,
     * When the connection loop runs in runOnce mode,
     * Then a WorkerRuntimeError.AssignedConfigBootstrap is returned.
     */
    @Test
    fun `bootstrap failure in runOnce mode returns AssignedConfigBootstrap error`() = runTest {
        val authManager = FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z")))
        val bootstrapError = AssignedConfigBootstrapError.FetchFailed(
            WorkerMcpServerApiError.UnexpectedHttpStatus("fetch", 500)
        )
        val bootstrapper = mockk<AssignedConfigBootstrapper>()
        coEvery { bootstrapper.bootstrap() } returns bootstrapError.left()

        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val config = fakeTransportConfig()

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, credentialBootstrapper(), config)
        val result = loop.run(runOnce = true)

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerRuntimeError.AssignedConfigBootstrap)
    }

    /**
     * Verifies that bootstrap success allows websocket session to start in runOnce mode.
     *
     * Given a bootstrapper that succeeds and a session runner that succeeds,
     * When the connection loop runs in runOnce mode,
     * Then the loop completes successfully.
     */
    @Test
    fun `bootstrap success allows websocket session start in runOnce mode`() = runTest {
        val authManager = FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z")))
        val bootstrapper = mockk<AssignedConfigBootstrapper>()
        coEvery { bootstrapper.bootstrap() } returns Unit.right()

        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val config = fakeTransportConfig()

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, credentialBootstrapper(), config)
        val result = loop.run(runOnce = true)

        assertTrue(result.isRight())
    }

    /**
     * Verifies that websocket token is acquired after successful bootstrap.
     *
     * Given a bootstrapper that succeeds,
     * When the connection loop runs in runOnce mode,
     * Then the auth manager token is retrieved after bootstrap step occurs.
     */
    @Test
    fun `websocket token is acquired after bootstrap step`() = runTest {
        val token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))
        val callOrder = mutableListOf<String>()

        val authManager = EventTrackingAuthManager(token = token, callOrder = callOrder)

        val bootstrapper = mockk<AssignedConfigBootstrapper>()
        coEvery { bootstrapper.bootstrap() } coAnswers {
            callOrder.add("bootstrap")
            Unit.right()
        }

        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val config = fakeTransportConfig()

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, credentialBootstrapper(), config)
        val result = loop.run(runOnce = true)

        assertTrue(result.isRight())
        // Verify ordering: bootstrap must occur before token acquisition
        val bootstrapIndex = callOrder.indexOfFirst { it == "bootstrap" }
        val tokenIndex = callOrder.indexOfFirst { it == "getValidToken" }
        assertTrue(bootstrapIndex >= 0, "Bootstrap should be called")
        assertTrue(tokenIndex >= 0, "Token should be retrieved")
        assertTrue(bootstrapIndex < tokenIndex, "Bootstrap must occur before token acquisition. Call order: $callOrder")
    }

    // Test helpers

    /**
     * Verifies that a credential bootstrap contradicting the pinned worker id ends the run in runOnce
     * mode before any session is opened.
     */
    @Test
    fun `a worker id mismatch ends the run in runOnce mode`() = runTest {
        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val loop = WebSocketConnectionLoop(
            FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))),
            sessionRunner,
            mcpBootstrapper(),
            credentialBootstrapper(assignedWorkerId = 9L, pinnedWorkerId = 2L),
            fakeTransportConfig()
        )

        val runtimeError = assertIs<WorkerRuntimeError.CredentialBootstrap>(
            assertIs<Either.Left<WorkerRuntimeError>>(loop.run(runOnce = true)).value
        )

        val mismatch = assertIs<AssignedCredentialBootstrapError.WorkerIdMismatch>(runtimeError.error)
        assertEquals(2L, mismatch.expected)
        assertEquals(9L, mismatch.actual)
        coVerify(exactly = 0) { sessionRunner.run(any()) }
    }

    /**
     * Verifies that the same mismatch also ends a long-running loop instead of reconnecting.
     */
    @Test
    fun `a worker id mismatch ends the run in normal mode`() = runTest {
        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val loop = WebSocketConnectionLoop(
            FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))),
            sessionRunner,
            mcpBootstrapper(),
            credentialBootstrapper(assignedWorkerId = 9L, pinnedWorkerId = 2L),
            fakeTransportConfig()
        )

        // The loop returns rather than sleeping, so this call cannot block the test.
        val runtimeError = assertIs<WorkerRuntimeError.CredentialBootstrap>(
            assertIs<Either.Left<WorkerRuntimeError>>(loop.run(runOnce = false)).value
        )

        val mismatch = assertIs<AssignedCredentialBootstrapError.WorkerIdMismatch>(runtimeError.error)
        assertEquals(2L, mismatch.expected)
        assertEquals(9L, mismatch.actual)
        coVerify(exactly = 0) { sessionRunner.run(any()) }
    }

    /**
     * Verifies that a credential fetch failure fails the run in runOnce mode, like the MCP bootstrap.
     */
    @Test
    fun `a credential fetch failure fails the run in runOnce mode`() = runTest {
        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val loop = WebSocketConnectionLoop(
            FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))),
            sessionRunner,
            mcpBootstrapper(),
            credentialBootstrapper(
                pinnedWorkerId = 2L,
                fetchError = WorkerCredentialApiError.UnexpectedHttpStatus("fetch", 500)
            ),
            fakeTransportConfig()
        )

        val error = assertIs<WorkerRuntimeError.CredentialBootstrap>(
            assertIs<Either.Left<WorkerRuntimeError>>(loop.run(runOnce = true)).value
        )

        assertIs<AssignedCredentialBootstrapError.FetchFailed>(error.error)
        coVerify(exactly = 0) { sessionRunner.run(any()) }
    }

    /**
     * Verifies that the long-running loop retries a credential fetch failure and opens no session until
     * the assignment has been fetched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a credential fetch failure retries before a session is opened`() = runTest {
        val api = mockk<WorkerCredentialApi>()
        coEvery { api.getAssignedCredentials() } returnsMany listOf(
            WorkerCredentialApiError.UnexpectedHttpStatus("fetch", 500).left(),
            AssignedCredentialsDto(workerId = 1L).right(),
        )
        val sessionRunner = mockk<WebSocketSessionRunner>()
        coEvery { sessionRunner.run(any()) } returns WebSocketSessionResult(stableConnection = true, authRejected = false)

        val loop = WebSocketConnectionLoop(
            FakeAuthManager(token = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))),
            sessionRunner,
            mcpBootstrapper(),
            AssignedCredentialBootstrapper(
                api = api,
                profileValidator = mockk(relaxed = true),
                store = InMemoryWorkerCredentialStore(),
                pinnedWorkerId = 1L,
            ),
            fakeTransportConfig()
        )

        backgroundScope.launch { loop.run(runOnce = false) }

        // The first cycle fails and no session is opened for it.
        runCurrent()
        coVerify(exactly = 0) { sessionRunner.run(any()) }

        // One backoff later the second attempt succeeds, and exactly one session is opened.
        advanceTimeBy(150.milliseconds)
        coVerify(exactly = 1) { sessionRunner.run(any()) }
    }

    /**
     * Builds an MCP bootstrapper that succeeds, so only the credential path is under test.
     *
     * @return Successful MCP bootstrapper.
     */
    private fun mcpBootstrapper(): AssignedConfigBootstrapper {
        val bootstrapper = mockk<AssignedConfigBootstrapper>()
        coEvery { bootstrapper.bootstrap() } returns Unit.right()
        return bootstrapper
    }

    /**
     * Builds a credential bootstrapper over a mocked API with no rows.
     *
     * @param assignedWorkerId Worker id the mocked response resolves.
     * @param pinnedWorkerId Worker id configured as `worker.identity.workerId`.
     * @param fetchError Optional fetch failure the mocked API reports instead of a response.
     * @return A bootstrapper wired to that assignment.
     */
    private fun credentialBootstrapper(
        assignedWorkerId: Long = 1L,
        pinnedWorkerId: Long? = assignedWorkerId,
        fetchError: WorkerCredentialApiError? = null,
    ): AssignedCredentialBootstrapper {
        val api = mockk<WorkerCredentialApi>()
        coEvery { api.getAssignedCredentials() } returns if (fetchError != null) {
            fetchError.left()
        } else {
            AssignedCredentialsDto(workerId = assignedWorkerId).right()
        }
        return AssignedCredentialBootstrapper(
            api = api,
            profileValidator = mockk(relaxed = true),
            store = InMemoryWorkerCredentialStore(),
            pinnedWorkerId = pinnedWorkerId,
        )
    }

    private fun fakeTransportConfig(): WebSocketTransportConfig {
        return WebSocketTransportConfig(
            workerUid = "test-worker",
            webSocketUrl = "ws://localhost:8080/ws",
            reconnectInitialDelayMs = 100L,
            reconnectMaxDelayMs = 10000L
        )
    }

    /**
     * Auth manager that tracks the order in which methods are called.
     *
     * Records each method call to a list for test assertions about sequencing.
     */
    private class EventTrackingAuthManager(
        private val token: StoredServiceToken? = null,
        private val error: WorkerAuthManagerError? = null,
        private val callOrder: MutableList<String>
    ) : WorkerAuthManager {
        override suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> {
            callOrder.add("getValidToken")
            return error?.left() ?: (token?.right() ?: throw IllegalStateException("No token configured"))
        }

        override suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> {
            callOrder.add("forceReauthenticate")
            return token?.right() ?: error?.left() ?: throw IllegalStateException("No token configured")
        }
    }

    /**
     * Fake [WorkerAuthManager] that returns pre-configured tokens.
     */
    private class FakeAuthManager(
        private val token: StoredServiceToken? = null,
        private val error: WorkerAuthManagerError? = null
    ) : WorkerAuthManager {
        var getValidTokenCalls = 0

        override suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> {
            getValidTokenCalls++
            return error?.left() ?: (token?.right() ?: throw IllegalStateException("No token configured"))
        }

        override suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> {
            return token?.right() ?: error?.left() ?: throw IllegalStateException("No token configured")
        }
    }
}
