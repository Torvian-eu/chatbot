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
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val path = parseRequiredString(input, "path", validationErrors)

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            try {
                Files.createDirectories(target)
                BuiltInToolExecutionResult(output = "Created directory $path")
            } catch (e: Exception) {
                builtInToolErrorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to create directory: ${e.message}")
            }
        }
    }
}