package eu.torvian.chatbot.worker.auth

import arrow.core.Either

/**
 * Persistence abstraction for the worker access token cache.
 *
 * Implementations are expected to store and retrieve the most recent service token so the
 * worker can reuse it on restart instead of always running the challenge flow.
 */
interface ServiceTokenStore {
    /**
     * Loads the cached token from persistent storage.
     *
     * @return The stored token, `null` if the cache is empty, or a logical storage error.
     */
    suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?>

    /**
     * Persists the latest token to storage.
     *
     * @param token Token to store for later reuse.
     * @return `Unit` on success or a logical storage error.
     */
    suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit>

    /**
     * Clears any cached token from storage.
     *
     * @return `Unit` on success or a logical storage error.
     */
    suspend fun clear(): Either<ServiceTokenStoreError, Unit>
}

