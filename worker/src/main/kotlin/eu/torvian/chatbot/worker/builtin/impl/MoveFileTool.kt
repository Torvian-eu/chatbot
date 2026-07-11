package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files

/**
 * Moves or renames a file or directory inside the worker workspace.
 *
 * Fails if the destination already exists.
 */
class MoveFileTool : BuiltInTool {
    override val name: String = "move_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val source = input["source"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: source")
        val destination = input["destination"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: destination")

        val sourcePath = try {
            WorkspacePathValidator.requireInside(context.workspace, source)
        } catch (e: Exception) {
            return errorResult(BuiltInToolExecutionError.WORKSPACE_VIOLATION, "source: ${e.message}")
        }
        val destinationPath = try {
            WorkspacePathValidator.requireInside(context.workspace, destination)
        } catch (e: Exception) {
            return errorResult(BuiltInToolExecutionError.WORKSPACE_VIOLATION, "destination: ${e.message}")
        }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(sourcePath)) {
                return@withContext errorResult(BuiltInToolExecutionError.NOT_FOUND, "Source does not exist: $source")
            }
            if (Files.exists(destinationPath)) {
                return@withContext errorResult(
                    BuiltInToolExecutionError.ALREADY_EXISTS,
                    "Destination already exists: $destination"
                )
            }

            try {
                Files.move(sourcePath, destinationPath)
                BuiltInToolExecutionResult(output = "Moved $source to $destination")
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to move: ${e.message}")
            }
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}
