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
 * Creates a new file or overwrites an existing file inside the worker workspace.
 */
class WriteFileTool : BuiltInTool {
    override val name: String = "write_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path", "content")
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
        val content = input["content"]?.jsonPrimitive?.content
        if (content == null) {
            validationErrors.add("Missing required argument: content")
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
                Files.createDirectories(target.parent ?: context.workspace)
                Files.writeString(target, content!!, Charsets.UTF_8)
                BuiltInToolExecutionResult(output = "Wrote ${content.length} bytes to $path")
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to write file: ${e.message}")
            }
        }
    }

    private fun errorResult(code: String, message: String, errorDetails: String? = null): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code, errorDetails = errorDetails)
}