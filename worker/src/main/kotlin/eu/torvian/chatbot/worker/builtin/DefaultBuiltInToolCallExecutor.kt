package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default in-memory implementation of [BuiltInToolCallExecutor].
 *
 * Resolves the unprefixed built-in tool name to a [BuiltInTool] implementation and dispatches the
 * call with the injected [context]. Unknown tool names return a structured
 * [BuiltInToolExecutionResult] error instead of throwing.
 *
 * Parses the raw [input] JSON string internally. If the input is malformed JSON, a structured
 * error with [BuiltInToolExecutionError.INVALID_INPUT] is returned immediately, matching the
 * pattern used by [eu.torvian.chatbot.worker.mcp.McpToolCallExecutorImpl].
 *
 * The set of available tools is supplied via [tools] (typically assembled in the Koin module) so
 * that adding a new built-in tool is purely additive and never requires modifying this dispatcher.
 *
 * @property context Execution context providing the worker workspace and command timeout.
 * @property tools Registry mapping the unprefixed [BuiltInTool.name] (e.g. `read_text_file`) to its
 *   implementation.
 */
class DefaultBuiltInToolCallExecutor(
    private val context: BuiltInToolExecutionContext,
    private val tools: Map<String, BuiltInTool>,
) : BuiltInToolCallExecutor {

    private companion object {
        private val logger: Logger = LogManager.getLogger(DefaultBuiltInToolCallExecutor::class.java)
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }
    override suspend fun execute(toolName: String, input: String?): BuiltInToolExecutionResult {
        val arguments = input
            ?.let { raw ->
                try {
                    jsonParser.parseToJsonElement(raw).jsonObject
                } catch (exception: Exception) {
                    return BuiltInToolExecutionResult(
                        isError = true,
                        errorMessage = "Malformed JSON input: ${exception.message}",
                        errorCode = BuiltInToolExecutionError.INVALID_INPUT,
                    )
                }
            }
            ?: JsonObject(emptyMap())

        val tool = tools[toolName]
        if (tool == null) {
            logger.warn("Unknown built-in tool requested: $toolName")
            return BuiltInToolExecutionResult(
                isError = true,
                errorMessage = "Unknown built-in tool: $toolName",
                errorCode = BuiltInToolExecutionError.UNKNOWN_TOOL,
            )
        }
        return try {
            tool.execute(arguments, context)
        } catch (e: WorkspaceSecurityViolation) {
            BuiltInToolExecutionResult(
                isError = true,
                errorMessage = e.message ?: "Workspace violation",
                errorCode = BuiltInToolExecutionError.WORKSPACE_VIOLATION,
            )
        } catch (e: Exception) {
            logger.error("Built-in tool ${tool.name} failed", e)
            BuiltInToolExecutionResult(
                isError = true,
                errorMessage = e.message ?: e::class.simpleName ?: "Execution failed",
                errorCode = BuiltInToolExecutionError.EXECUTION_FAILED,
            )
        }
    }

}
