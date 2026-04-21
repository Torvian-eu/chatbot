package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default implementation of [WorkerAuthenticatedRequestExecutor].
 *
 * Acquires a token, executes the operation, and retries with forced reauth on 401/403.
 */
class DefaultWorkerAuthenticatedRequestExecutor(
    /**
     * Source for token acquisition and forced reauth.
     *
     * Used to obtain a valid token before each operation and to force reauth on 401/403 responses.
     */
    private val authManager: WorkerAuthManager
) : WorkerAuthenticatedRequestExecutor {

    override suspend fun <T> execute(
        operation: String,
        block: suspend (accessToken: String) -> T
    ): Either<WorkerAuthenticatedRequestError, T> = either {
        logger.debug("Acquiring valid token for authenticated operation (operation={})", operation)
        val initialToken = authManager.getValidToken()
            .mapLeft { authManagerError ->
                logger.warn("Failed to acquire initial token (operation={}, error={})", operation, authManagerError)
                WorkerAuthenticatedRequestError.Auth(authManagerError)
            }
            .bind()

        logger.debug("Executing authenticated operation (operation={})", operation)
        executeOnce(operation, initialToken.accessToken, block)
            .onLeft { error ->
                if (isAuthRejection(error)) {
                    // Trigger retry logic for 401/403 errors.
                    logger.info(
                        "Authenticated operation rejected by server (operation={}, statusCode={}); initiating retry logic",
                        operation,
                        (error as WorkerAuthenticatedRequestError.HttpStatus).statusCode
                    )
                    return@either retryAfterAuthRejection(operation, error, block)
                } else {
                    // Non-auth error: log and return without retry.
                    logger.warn(
                        "Authenticated operation failed with non-auth error (operation={}, error={}); returning without retry",
                        operation,
                        error
                    )
                }
            }
            .onRight {
                logger.debug("Operation succeeded (operation={})", operation)
            }.bind()
    }

    /**
     * Checks whether an error is an HTTP auth rejection (401 or 403).
     *
     * @param error The error to check.
     * @return `true` if the error is a 401/403 HttpStatus error; `false` otherwise.
     */
    private fun isAuthRejection(error: WorkerAuthenticatedRequestError): Boolean =
        error is WorkerAuthenticatedRequestError.HttpStatus &&
                (error.statusCode == 401 || error.statusCode == 403)

    /**
     * Attempts forced reauth and retries the operation exactly once.
     *
     * If forced reauth succeeds, executes the operation again with the refreshed token.
     * If forced reauth fails, the error is converted to [WorkerAuthenticatedRequestError.Auth].
     * If the retry fails, the retry error is returned as-is.
     *
     * @param operation Human-readable operation name for logging context.
     * @param initialError The initial 401/403 error that triggered this retry.
     * @param block The operation to retry with the refreshed token.
     * @return Either a logical error or the successful operation result.
     */
    private suspend fun <T> Raise<WorkerAuthenticatedRequestError>.retryAfterAuthRejection(
        operation: String,
        initialError: WorkerAuthenticatedRequestError.HttpStatus,
        block: suspend (accessToken: String) -> T
    ): T {
        logger.info(
            "Auth rejected by server (operation={}, statusCode={}); forcing reauth and retrying once",
            operation,
            initialError.statusCode
        )

        val refreshedToken = authManager.forceReauthenticate()
            .mapLeft { reauthError ->
                logger.warn(
                    "Failed to force reauth (operation={}, error={})",
                    operation,
                    reauthError
                )
                WorkerAuthenticatedRequestError.Auth(reauthError)
            }
            .bind()

        logger.debug("Retrying operation with refreshed token (operation={})", operation)
        val result = executeOnce(operation, refreshedToken.accessToken, block).bind()

        logger.debug("Retry succeeded (operation={})", operation)
        return result
    }

    /**
     * Executes the operation once with the provided token.
     *
     * Catches and maps HTTP and transport exceptions into logical [WorkerAuthenticatedRequestError] values.
     * Does not perform retry; the caller is responsible for 401/403 retry logic.
     *
     * @param operation Human-readable operation name used for error context.
     * @param accessToken Access token to pass to the operation block.
     * @param block The operation to execute with the provided token.
     * @return Either a logical error or the successful operation result.
     */
    private suspend fun <T> executeOnce(
        operation: String,
        accessToken: String,
        block: suspend (accessToken: String) -> T
    ): Either<WorkerAuthenticatedRequestError, T> = either {
        catch({ block(accessToken) }) { e ->
            when (e) {
                is ResponseException -> {
                    val bodyText = runCatching { e.response.bodyAsText() }.getOrNull()
                    val statusCode = e.response.status.value
                    logger.warn(
                        "Authenticated request failed with HTTP status (operation={}, statusCode={}, hasBody={})",
                        operation,
                        statusCode,
                        bodyText != null
                    )
                    raise(
                        WorkerAuthenticatedRequestError.HttpStatus(
                            operation = operation,
                            statusCode = statusCode,
                            responseBody = bodyText
                        )
                    )
                }

                else -> {
                    logger.warn("Authenticated request failed with exception (operation={})", operation, e)
                    raise(
                        WorkerAuthenticatedRequestError.Transport(
                            operation = operation,
                            reason = e.message ?: e::class.simpleName.orEmpty()
                        )
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Logger used for authenticated request execution diagnostics.
         *
         * Logs token acquisition, forced reauth, HTTP status errors, and transport failures.
         */
        private val logger: Logger = LogManager.getLogger(DefaultWorkerAuthenticatedRequestExecutor::class.java)
    }
}