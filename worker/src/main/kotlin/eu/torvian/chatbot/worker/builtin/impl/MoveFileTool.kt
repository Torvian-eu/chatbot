package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import eu.torvian.chatbot.worker.builtin.validation.addUnknownParameterErrors
import eu.torvian.chatbot.worker.builtin.validation.builtInToolErrorResult
import eu.torvian.chatbot.worker.builtin.validation.invalidInputResult
import eu.torvian.chatbot.worker.builtin.validation.parseRequiredString
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
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("source", "destination")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val source = parseRequiredString(input, "source", validationErrors)
        val destination = parseRequiredString(input, "destination", validationErrors)

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val sourcePath = try {
            WorkspacePathValidator.requireInside(context.workspace, source!!)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                "source: ${e.message}"
            )
        }
        val destinationPath = try {
            WorkspacePathValidator.requireInside(context.workspace, destination!!)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                "destination: ${e.message}"
            )
        }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(sourcePath)) {
                return@withContext builtInToolErrorResult(BuiltInToolExecutionError.NOT_FOUND, "Source does not exist: $source")
            }
            if (Files.exists(destinationPath)) {
                return@withContext builtInToolErrorResult(
                    BuiltInToolExecutionError.ALREADY_EXISTS,
                    "Destination already exists: $destination"
                )
            }

            try {
                Files.move(sourcePath, destinationPath)
                BuiltInToolExecutionResult(output = "Moved $source to $destination")
            } catch (e: Exception) {
                builtInToolErrorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to move: ${e.message}")
            }
        }
    }
}