package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.impl.*
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default in-memory implementation of [BuiltInToolCallExecutor].
 *
 * Resolves the unprefixed built-in tool name to a [BuiltInTool] implementation and dispatches the
 * call with the injected [context]. Unknown tool names return a structured
 * [BuiltInToolExecutionResult] error instead of throwing.
 *
 */
class DefaultBuiltInToolCallExecutor(
    private val context: BuiltInToolExecutionContext,
) : BuiltInToolCallExecutor {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultBuiltInToolCallExecutor::class.java)

        /**
         * Builds the default registry of built-in tools. The key is the unprefixed
         * [BuiltInTool.name] (e.g. `read_text_file`).
         */
        fun defaultTools(): Map<String, BuiltInTool> = mapOf(
            "read_text_file" to ReadTextFileTool(),
            "write_file" to WriteFileTool(),
            "edit_file" to EditFileTool(),
            "create_directory" to CreateDirectoryTool(),
            "list_directory" to ListDirectoryTool(),
            "move_file" to MoveFileTool(),
            "search_files" to SearchFilesTool(),
            "run_command" to RunCommandTool(),
        )
    }

    private val tools: Map<String, BuiltInTool> = defaultTools()

    override suspend fun execute(toolName: String, input: JsonObject): BuiltInToolExecutionResult {
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
            tool.execute(input, context)
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
