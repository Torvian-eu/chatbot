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
 * Creates a directory (and any missing parents) inside the worker workspace. Idempotent: succeeds
 * silently if the directory already exists.
 */
class CreateDirectoryTool : BuiltInTool {
    override val name: String = "create_directory"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path")
        // Check for unknown parameters
        for (key in input.keys) {
            if (key !in validKeys) {
                validationErrors.add("Unknown parameter: '$key'")
            }
        }

        val path = input["path"]?.jsonPrimitive?.content
        if (path == null) {
            validationErrors.add("Missing required argument: path")
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

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            try {
                Files.createDirectories(target)
                BuiltInToolExecutionResult(output = "Created directory $path")
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to create directory: ${e.message}")
            }
        }
    }

    private fun errorResult(code: String, message: String, errorDetails: String? = null): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code, errorDetails = errorDetails)
}