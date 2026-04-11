package eu.torvian.chatbot.worker.auth

import arrow.core.Either

/**
 * Resolves and refreshes worker access tokens.
 */
interface WorkerAuthManager {
    /**
     * Returns a usable token, loading from cache first and falling back to challenge-response.
     *
     * @return Either a logical auth error or a valid token.
     */
    suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken>

    /**
     * Forces a fresh challenge flow, ignoring any cached token.
     *
     * @return Either a logical auth error or a new token.
     */
    suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken>
}


