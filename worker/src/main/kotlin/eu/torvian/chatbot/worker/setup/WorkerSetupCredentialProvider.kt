package eu.torvian.chatbot.worker.setup

import arrow.core.Either

/**
 * Resolves user credentials required by setup to register a worker with the server.
 *
 * Implementations may use environment variables, interactive prompts, secret stores, or
 * other secure sources, but they must return credentials suitable only for the setup flow.
 */
interface WorkerSetupCredentialProvider {
    /**
     * Resolves credentials for the setup-time login flow.
     *
     * @return Either a logical setup error or the resolved username/password pair.
     */
    suspend fun resolveCredentials(): Either<WorkerSetupError, WorkerSetupCredentials>
}

