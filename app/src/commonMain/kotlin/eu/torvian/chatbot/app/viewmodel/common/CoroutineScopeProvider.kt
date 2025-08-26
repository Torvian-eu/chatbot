package eu.torvian.chatbot.app.viewmodel.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Provider for creating CoroutineScopes with proper configuration.
 * This allows for easier testing by injecting different scopes in tests.
 */
interface CoroutineScopeProvider {
    /**
     * Creates a CoroutineScope suitable for normal UI operations.
     * Uses SupervisorJob + Main dispatcher.
     */
    fun createNormalScope(): CoroutineScope

    /**
     * Creates a CoroutineScope suitable for background operations.
     * Uses SupervisorJob + Main dispatcher (for now, but can be changed later).
     */
    fun createBackgroundScope(): CoroutineScope
}

/**
 * Default implementation that creates standard CoroutineScopes.
 */
class DefaultCoroutineScopeProvider : CoroutineScopeProvider {
    override fun createNormalScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun createBackgroundScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
