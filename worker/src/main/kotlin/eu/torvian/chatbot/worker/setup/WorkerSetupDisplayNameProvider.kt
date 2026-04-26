package eu.torvian.chatbot.worker.setup

import arrow.core.Either

/**
 * Resolves the worker display name required during setup.
 *
 * Implementations may use environment variables, interactive prompts, or configuration
 * defaults. The resolved name is forwarded to the server when the worker is registered.
 */
interface WorkerSetupDisplayNameProvider {

    /**
     * Resolves the display name for the worker registration step.
     *
     * @param defaultDisplayName Fallback value when the user does not supply an override.
     * @return Either a logical setup error or the resolved display name.
     */
    suspend fun resolveDisplayName(defaultDisplayName: String): Either<WorkerSetupError, String>
}
