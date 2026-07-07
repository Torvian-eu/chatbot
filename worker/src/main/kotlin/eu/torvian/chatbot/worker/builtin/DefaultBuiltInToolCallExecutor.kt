package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.impl.CreateDirectoryTool
import eu.torvian.chatbot.worker.builtin.impl.EditFileTool
import eu.torvian.chatbot.worker.builtin.impl.ListDirectoryTool
import eu.torvian.chatbot.worker.builtin.impl.MoveFileTool
import eu.torvian.chatbot.worker.builtin.impl.ReadTextFileTool
import eu.torvian.chatbot.worker.builtin.impl.RunCommandTool
import eu.torvian.chatbot.worker.builtin.impl.SearchFilesTool
import eu.torvian.chatbot.worker.builtin.impl.WriteFileTool
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default in-memory implementation of [BuiltInToolCallExecutor].
 *
 * Resolves the public tool name (which may carry a configured prefix) to a [BuiltInTool]
 * implementation, looks up the appropriate execution context, and dispatches the call. Unknown
 * tool names return a structured [BuiltInToolExecutionResult] error instead of throwing.
 */
class DefaultBuiltInToolCallExecutor(
    private val contextProvider: () -> BuiltInToolExecutionContext,
) : BuiltInToolCallExecutor {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultBuiltInToolCallExecutor::class.java)

        /**
         * Builds the default registry of built-in tools. The public key in the registry is the
         * tool's unprefixed [BuiltInTool.name] — the prefix is applied at lookup time.
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

    override suspend fun execute(request: BuiltInToolExecutionRequest): BuiltInToolExecutionResult {
        val context = contextProvider()
        val unprefixed = unprefix(request.toolName, context.toolNamePrefix)
        val tool = tools[unprefixed]
        if (tool == null) {
            logger.warn("Unknown built-in tool requested: ${request.toolName}")
            return BuiltInToolExecutionResult(
                isError = true,
                errorMessage = "Unknown built-in tool: ${request.toolName}",
                errorCode = BuiltInToolExecutionError.UNKNOWN_TOOL,
            )
        }
        return try {
            tool.execute(request.input, context)
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

    /**
     * Strips the configured prefix from [publicName] to obtain the unprefixed registry key.
     */
    private fun unprefix(publicName: String, prefix: String?): String {
        if (prefix.isNullOrBlank()) return publicName
        val expected = "$prefix."
        return if (publicName.startsWith(expected)) publicName.substring(expected.length) else publicName
    }
}

