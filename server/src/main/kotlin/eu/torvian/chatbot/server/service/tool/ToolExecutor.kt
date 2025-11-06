package eu.torvian.chatbot.server.service.tool

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError

/**
 * Interface for executing tool calls.
 * Each tool type (e.g., WEB_SEARCH, CALCULATOR) should have its own implementation.
 */
interface ToolExecutor {
    /**
     * Executes a tool with the given input and returns the result.
     * @param toolDefinition The definition of the tool to execute, including configuration.
     * @param inputJson The raw JSON string containing the input arguments for the tool.
     *                  May be null or invalid JSON. The executor is responsible for parsing and validation.
     * @return Either a [ToolExecutionError] if execution fails, or the result as a Json string (may be invalid JSON).
     */
    suspend fun executeTool(
        toolDefinition: ToolDefinition,
        inputJson: String?
    ): Either<ToolExecutionError, String>

    /**
     * Validates that the executor can handle the given tool definition.
     * @param toolDefinition The tool definition to validate.
     * @return Either a [ToolExecutionError.InvalidConfiguration] if invalid, or Unit if valid.
     */
    fun validateConfiguration(toolDefinition: ToolDefinition): Either<ToolExecutionError.InvalidConfiguration, Unit>
}

