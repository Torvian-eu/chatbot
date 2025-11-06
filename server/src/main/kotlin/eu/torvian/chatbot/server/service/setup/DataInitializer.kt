package eu.torvian.chatbot.server.service.setup

import arrow.core.Either

/**
 * Interface for components that perform initial data setup during application startup.
 *
 * Each initializer is responsible for setting up a specific domain of data
 * (e.g., user accounts, tool definitions, default configurations).
 */
interface DataInitializer {
    /**
     * Returns the name of this initializer for logging purposes.
     */
    val name: String

    /**
     * Checks if this initializer has already run by examining the database.
     * @return true if the setup is already complete, false otherwise
     */
    suspend fun isInitialized(): Boolean

    /**
     * Performs the initial data setup for this domain.
     * This method should be idempotent - calling it multiple times should be safe.
     *
     * @return Either an error message if setup fails, or Unit on success
     */
    suspend fun initialize(): Either<String, Unit>
}

