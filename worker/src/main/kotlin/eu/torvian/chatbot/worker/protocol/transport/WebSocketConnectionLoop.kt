package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Maintains an authenticated worker WebSocket session with reconnect and re-auth behavior.
 *
 * @property authManager Auth manager used to fetch and refresh service tokens.
 * @property sessionRunner Runner that executes one concrete WebSocket session.
 * @property transportConfig Reconnect timing configuration.
 * @property randomProvider Random source used to apply reconnect jitter.
 */
class WebSocketConnectionLoop(
    private val authManager: WorkerAuthManager,
    private val sessionRunner: WebSocketSessionRunner,
    private val transportConfig: WebSocketTransportConfig,
    private val randomProvider: () -> Double = { Random.nextDouble() }
) : TransportConnectionLoopRunner {
    /**
     * Executes the connection loop until cancellation, or one cycle when `runOnce` is requested.
     *
     * @param runOnce When `true`, performs one authenticated connect attempt and returns.
     * @return Either an auth logical error (only in one-shot mode) or `Unit`.
     */
    override suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit> {
        var delayMs = transportConfig.reconnectInitialDelayMs.coerceAtLeast(100L)
        var forceReauthenticate = false

        try {
            while (currentCoroutineContext().isActive) {
                val tokenResult = if (forceReauthenticate) {
                    logger.info("Attempting worker re-authentication before reconnect")
                    authManager.forceReauthenticate()
                } else {
                    authManager.getValidToken()
                }

                val token = when (tokenResult) {
                    is Either.Right -> tokenResult.value
                    is Either.Left -> {
                        if (runOnce) {
                            return Either.Left(tokenResult.value)
                        }

                        logger.warn("Worker authentication attempt failed; scheduling retry (error={})", tokenResult.value)
                        delay(withJitter(delayMs))
                        delayMs = nextDelay(delayMs)
                        forceReauthenticate = true
                        continue
                    }
                }

                forceReauthenticate = false
                val sessionResult = sessionRunner.run(token.accessToken)

                if (runOnce) {
                    return Either.Right(Unit)
                }

                if (sessionResult.authRejected) {
                    logger.warn("Worker WebSocket connection rejected by server auth; forcing re-authentication")
                    forceReauthenticate = true
                }

                if (sessionResult.stableConnection) {
                    delayMs = transportConfig.reconnectInitialDelayMs.coerceAtLeast(100L)
                } else {
                    delayMs = nextDelay(delayMs)
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
         */
        private val logger: Logger = LogManager.getLogger(WebSocketConnectionLoop::class.java)
    }
}



