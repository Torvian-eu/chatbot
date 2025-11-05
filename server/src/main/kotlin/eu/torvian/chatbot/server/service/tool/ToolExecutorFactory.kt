package eu.torvian.chatbot.server.service.tool

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Factory for creating tool executors based on tool type.
 * Manages the registry of available executors.
 */
class ToolExecutorFactory(
    private val webSearchExecutor: ToolExecutor,
    private val weatherExecutor: ToolExecutor
    // Add more executors as they are implemented
) {
    private val logger: Logger = LogManager.getLogger(ToolExecutorFactory::class.java)

    private val executors: Map<ToolType, ToolExecutor> = mapOf(
        ToolType.WEB_SEARCH to webSearchExecutor,
        ToolType.WEATHER to weatherExecutor
        // ToolType.CALCULATOR to calculatorExecutor,
        // etc.
    )

    /**
     * Gets the appropriate executor for the given tool type.
     * @param toolType The type of tool to execute.
     * @return Either an [ToolExecutionError.UnsupportedToolType] if no executor is registered,
     *         or the [ToolExecutor] instance.
     */
    fun getExecutor(toolType: ToolType): Either<ToolExecutionError.UnsupportedToolType, ToolExecutor> {
        val executor = executors[toolType]
        return if (executor != null) {
            logger.debug("Found executor for tool type: $toolType")
            executor.right()
        } else {
            logger.warn("No executor registered for tool type: $toolType")
            ToolExecutionError.UnsupportedToolType(toolType.name).left()
        }
    }

    /**
     * Checks if an executor is registered for the given tool type.
     * @param toolType The type of tool to check.
     * @return True if an executor is registered, false otherwise.
     */
    fun hasExecutor(toolType: ToolType): Boolean {
        return executors.containsKey(toolType)
    }
}

