package eu.torvian.chatbot.app.startup

/**
 * Generic initializer that runs once when the application reaches the ready state.
 *
 * Implementations can perform eager startup tasks such as ensuring device identity
 * material is present and logging diagnostic information. The initializer is
 * expected to be idempotent: calling [initialize] multiple times must not cause
 * duplicate side effects within the same process lifetime.
 */
interface AppStartupInitializer {

    /**
     * Performs startup initialization.
     *
     * This method is safe to call multiple times within a single process
     * lifetime; subsequent calls must be no-ops.
     */
    suspend fun initialize()
}
