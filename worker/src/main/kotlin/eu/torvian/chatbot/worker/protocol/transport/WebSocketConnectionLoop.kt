package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapper
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Maintains an authenticated worker WebSocket session with reconnect, re-auth, and bootstrap behavior.
 *
 * Orchestrates the per-cycle startup sequence:
 * 1. Bootstrap assigned MCP configs (using authenticated executor with token refresh)
 * 2. Get current valid token (ensures bootstrap refresh is accounted for)
 * 3. Open WebSocket session with that token
 * 4. On auth rejection, set flag for next-cycle forced reauth
 * 5. On any session failure, schedule reconnect with backoff
 */
class WebSocketConnectionLoop(
    /**
     * Auth manager used to fetch and refresh service tokens.
     *
     * Called before each bootstrap and websocket session attempt to obtain a valid token.
     */
    private val authManager: WorkerAuthManager,

    /**
     * Runner that executes one concrete WebSocket session.
     *
     * Called after successful bootstrap with a freshly acquired token.
     * Responsible for socket lifecycle, hello handshake, and frame processing.
     */
    private val sessionRunner: WebSocketSessionRunner,

    /**
     * Bootstrapper for assigned MCP server configurations.
     *
     * Called before each websocket session to fetch and cache server configurations.
     * Failures cause cycle abort; outer loop handles reconnect and backoff.
     */
    private val bootstrapper: AssignedConfigBootstrapper,

    /**
     * Reconnect timing configuration.
     *
     * Provides initial, maximum, and jitter settings for backoff calculations
     * between failed connection attempts.
     */
    private val transportConfig: WebSocketTransportConfig,

    /**
     * Random source used to apply reconnect jitter.
     *
     * Allows tests to inject deterministic randomness; defaults to [Random.nextDouble].
     */
    private val randomProvider: () -> Double = { Random.nextDouble() }
) : TransportConnectionLoopRunner {
    /**
     * Executes the connection loop until cancellation, or one cycle when `runOnce` is requested.
     *
     * Per-cycle orchestration:
     * 1. Acquire or refresh token
     * 2. Bootstrap assigned MCP configurations
     * 3. Acquire fresh token (to account for bootstrap-triggered reauth)
     * 4. Open and run WebSocket session
     * 5. Schedule reconnect on any failure
     *
     * @param runOnce When `true`, performs one authenticated connect attempt and returns.
     * @return Either a startup/runtime error or `Unit`.
     *         In runOnce mode, any startup failure (auth or bootstrap) is returned as a Left.
     */
    override suspend fun run(runOnce: Boolean): Either<WorkerRuntimeError, Unit> {
        var delayMs = transportConfig.reconnectInitialDelayMs.coerceAtLeast(100L)
        var forceReauthenticate = false

        try {
            while (currentCoroutineContext().isActive) {
                // Step 1: Acquire or refresh auth token.
                // If previous session was auth-rejected, force a fresh token.
                if (forceReauthenticate) {
                    logger.info("Forcing worker re-authentication due to previous auth rejection")
                    when (val reauthResult = authManager.forceReauthenticate()) {
                        is Either.Right -> logger.debug("Worker forced re-authentication succeeded")
                        is Either.Left -> {
                            logger.warn("Worker forced re-authentication failed; scheduling retry (error={})", reauthResult.value)
                            delay(withJitter(delayMs))
                            delayMs = nextDelay(delayMs)
                            continue
                        }
                    }
                    forceReauthenticate = false
                }

                // Step 2: Bootstrap assigned MCP configurations.
                // This uses WorkerAuthenticatedRequestExecutor internally, so no pre-fetch needed.
                logger.debug("Starting assigned MCP server configuration bootstrap")
                when (val bootstrapResult = bootstrapper.bootstrap()) {
                    is Either.Right -> {
                        logger.info("Successfully bootstrapped assigned MCP server configurations")
                    }
                    is Either.Left -> {
                        val error = bootstrapResult.value
                        logger.warn("Failed to bootstrap assigned MCP server configurations: {}", error)
                        if (runOnce) {
                            return WorkerRuntimeError.AssignedConfigBootstrap(error).left()
                        }
                        val reconnectDelay = withJitter(delayMs)
                        logger.info("Retrying bootstrap in {} ms", reconnectDelay)
                        delay(reconnectDelay)
                        delayMs = nextDelay(delayMs)
                        continue
                    }
                }

                // Step 3: Acquire fresh token for websocket session.
                // This ensures any token refresh triggered by bootstrap is reflected.
                logger.debug("Acquiring fresh token for websocket session (post-bootstrap)")
                val wsToken = when (val wsTokenResult = authManager.getValidToken()) {
                    is Either.Right -> {
                        logger.debug("Acquired fresh token for websocket session")
                        wsTokenResult.value
                    }
                    is Either.Left -> {
                        logger.warn("Failed to acquire websocket token; scheduling retry (error={})", wsTokenResult.value)
                        if (runOnce) {
                            return WorkerRuntimeError.Auth(wsTokenResult.value).left()
                        }
                        delay(withJitter(delayMs))
                        delayMs = nextDelay(delayMs)
                        forceReauthenticate = true
                        continue
                    }
                }

                val sessionResult = sessionRunner.run(wsToken.accessToken)

                if (runOnce) {
                    return Unit.right()
                }

                if (sessionResult.authRejected) {
                    logger.warn("Worker WebSocket connection rejected by server auth; forcing re-authentication")
                    forceReauthenticate = true
                }

                delayMs = if (sessionResult.stableConnection) {
                    transportConfig.reconnectInitialDelayMs.coerceAtLeast(100L)
                } else {
                    nextDelay(delayMs)
                }

                val reconnectDelay = withJitter(delayMs)
                logger.info("Reconnecting worker WebSocket in {} ms", reconnectDelay)
                delay(reconnectDelay)
            }
        } catch (_: CancellationException) {
            logger.info("Worker WebSocket connection loop cancelled")
        }

        return Either.Right(Unit)
    }

    /**
     * Returns the next bounded reconnect delay after one unsuccessful attempt.
     *
     * @param currentDelayMs Current reconnect delay value.
     * @return Next reconnect delay with exponential growth and max cap applied.
     */
    private fun nextDelay(currentDelayMs: Long): Long {
        val maxDelay = transportConfig.reconnectMaxDelayMs.coerceAtLeast(currentDelayMs)
        return (currentDelayMs * 2).coerceAtMost(maxDelay)
    }

    /**
     * Applies symmetric jitter around the current delay to avoid reconnect thundering herds.
     *
     * @param delayMs Base delay in milliseconds.
     * @return Delay adjusted by configured jitter ratio.
     */
    private fun withJitter(delayMs: Long): Long {
        val jitterRatio = transportConfig.reconnectJitterRatio.coerceIn(0.0, 1.0)
        if (jitterRatio == 0.0) {
            return delayMs
        }

        val spread = delayMs * jitterRatio
        val randomUnit = randomProvider().coerceIn(0.0, 1.0)
        val offset = ((randomUnit * 2.0) - 1.0) * spread
        return (delayMs + offset).roundToLong().coerceAtLeast(100L)
    }

    companion object {
        /**
         * Logger used for reconnect and auth-loop diagnostics.
         *
         * Logs connection attempts, auth failures, bootstrap progress, reconnect scheduling,
         * and session lifecycle events.
         */
        private val logger: Logger = LogManager.getLogger(WebSocketConnectionLoop::class.java)
    }
}



