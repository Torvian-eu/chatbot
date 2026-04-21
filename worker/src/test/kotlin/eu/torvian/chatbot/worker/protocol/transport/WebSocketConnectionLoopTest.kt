package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.auth.StoredServiceToken
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapError
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapper
import eu.torvian.chatbot.worker.mcp.api.WorkerMcpServerApiError
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
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

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, config)
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

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, config)
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

        val loop = WebSocketConnectionLoop(authManager, sessionRunner, bootstrapper, config)
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
