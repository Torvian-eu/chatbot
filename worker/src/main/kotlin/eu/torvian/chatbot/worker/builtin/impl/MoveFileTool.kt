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
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("source", "destination")
        // Check for unknown parameters
        for (key in input.keys) {
            if (key !in validKeys) {
                validationErrors.add("Unknown parameter: '$key'")
            }
        }

        val source = input["source"]?.jsonPrimitive?.content
        if (source == null) {
            validationErrors.add("Missing required argument: source")
        }
        val destination = input["destination"]?.jsonPrimitive?.content
        if (destination == null) {
            validationErrors.add("Missing required argument: destination")
        }

        if (validationErrors.isNotEmpty()) {
            return errorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Input validation failed with ${validationErrors.size} error(s):",
                errorDetails = buildJsonObject {
                    putJsonArray("validationErrors") {
                        validationErrors.forEach { error -> add(error) }
                    }
                }.toString()
            )
        }

        val sourcePath = try {
            WorkspacePathValidator.requireInside(context.workspace, source!!)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                "source: ${e.message}"
            )
        }
        val destinationPath = try {
            WorkspacePathValidator.requireInside(context.workspace, destination!!)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                "destination: ${e.message}"
            )
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

    private fun errorResult(code: String, message: String, errorDetails: String? = null): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code, errorDetails = errorDetails)
}