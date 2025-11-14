package eu.torvian.chatbot.server.service.setup

import arrow.core.Either
import arrow.core.raise.either
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Coordinates the execution of all data initializers during application startup.
 *
 * This service orchestrates multiple [DataInitializer] instances, running them in order
 * and aggregating their results. Each initializer is responsible for a specific domain
 * of initial data (e.g., user accounts, tool definitions).
 */
class InitializationCoordinator(
    private val initializers: List<DataInitializer>
) {
    private val logger: Logger = LogManager.getLogger(InitializationCoordinator::class.java)

    /**
     * Runs all registered initializers in sequence.
     * If any initializer fails, the process stops and returns the error.
     *
     * @return Either an error message describing which initializer failed,
     *         or Unit if all initializers completed successfully
     */
    suspend fun runAllInitializers(): Either<String, Unit> = either {
        logger.info("Starting initialization process with ${initializers.size} initializer(s)")

        for (initializer in initializers) {
            logger.info("Running initializer: ${initializer.name}")

            if (initializer.isInitialized()) {
                logger.info("${initializer.name} has already been initialized, skipping")
                continue
            }

            initializer.initialize().fold(
                ifLeft = { error ->
                    val errorMsg = "Failed to initialize ${initializer.name}: $error"
                    logger.error(errorMsg)
                    raise(errorMsg)
                },
                ifRight = {
                    logger.info("${initializer.name} completed successfully")
                }
            )
        }

        logger.info("All initializers completed successfully")
    }

    /**
     * Checks if all initializers have been run.
     * @return true if all initializers report they are initialized, false otherwise
     */
    suspend fun isFullyInitialized(): Boolean {
        return initializers.all { it.isInitialized() }
    }
}

