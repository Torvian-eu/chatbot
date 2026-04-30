package eu.torvian.chatbot.worker.setup

import arrow.core.Either

/**
 * Resolves the server URL required during setup.
 *
 * Implementations may use environment variables, configuration files, or interactive prompts.
 * The resolved URL is used to connect to the server during worker registration.
 */
interface WorkerSetupServerUrlProvider {

    /**
     * Resolves the server URL for the worker registration step.
     *
     * @param defaultServerUrl Fallback value when the user does not supply an override.
     * @return Either a logical setup error or the resolved server URL.
     */
    suspend fun resolveServerUrl(defaultServerUrl: String): Either<WorkerSetupError, String>
}
