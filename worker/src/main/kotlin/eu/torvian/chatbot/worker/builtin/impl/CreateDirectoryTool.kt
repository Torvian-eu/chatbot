package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Creates a directory (and any missing parents) inside the worker workspace. Idempotent: succeeds
 * silently if the directory already exists.
 */
class CreateDirectoryTool : BuiltInTool {
    override val name: String = "create_directory"
    override val description: String = "Create a directory (and parents) inside the workspace. Idempotent."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Path of the directory to create, relative to the workspace.")
            })
        })
        put("required", buildJsonObject { put("0", "path") })
    }

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val path = input["path"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: path")

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(BuiltInToolExecutionError.WORKSPACE_VIOLATION, e.message ?: "Path rejected by workspace validator")
        }

        return try {
            Files.createDirectories(target)
            BuiltInToolExecutionResult(output = "Created directory $path")
        } catch (e: Exception) {
            errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to create directory: ${e.message}")
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}

