package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Default [WorkerRuntime] that keeps the worker token authenticated and refreshed.
 *
 * @property workerId Worker ID used for logging.
 * @property refreshSkew Time before token expiration when a refresh should be attempted.
 * @property authManager Auth manager used for token retrieval and refresh.
 * @property nowProvider Provides current time for refresh scheduling. Defaults to system clock.
 */
class WorkerRuntimeImpl(
    private val workerId: Long,
    private val refreshSkew: Duration,
    private val authManager: WorkerAuthManager,
    private val nowProvider: () -> Instant = { Clock.System.now() }
) : WorkerRuntime {

    companion object {
        /**
         * Minimum delay between refresh attempts to prevent tight loops in case of clock issues or persistent auth failures.
         */
        private const val MIN_REFRESH_DELAY_MS = 5_000L
        private val logger: Logger = LogManager.getLogger(WorkerRuntimeImpl::class.java)
    }

    override suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit> = either {
        val token = authManager.getValidToken().bind()
        logger.info("Worker authenticated (workerId={}, expiresAt={})", workerId, token.expiresAt)

        if (runOnce) {
            logger.info("--once specified, exiting after authentication.")
            return@either
        }

        // Keep credentials fresh even when no workload is running yet.
        try {
            while (currentCoroutineContext().isActive) {
                val currentToken = authManager.getValidToken().bind()
                val refreshAt = currentToken.expiresAt.minus(refreshSkew)
                val sleepMs = (refreshAt - nowProvider()).inWholeMilliseconds.coerceAtLeast(MIN_REFRESH_DELAY_MS)
                logger.debug("Worker token refresh scheduled in {} ms", sleepMs)
                delay(sleepMs)

                val refreshed = authManager.getValidToken().bind()
                logger.info("Worker token checked/refreshed (workerId={}, expiresAt={})", workerId, refreshed.expiresAt)
            }
        } catch (_: CancellationException) {
            logger.info("Worker shutdown requested")
        }
    }
}

