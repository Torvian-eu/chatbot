package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Creates a new file or overwrites an existing file inside the worker workspace.
 */
class WriteFileTool : BuiltInTool {
    override val name: String = "write_file"
    override val description: String = "Create or overwrite a text file inside the workspace."
    override val inputSchema: JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("type", "object")
        put("properties", kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.buildJsonObject {
                put("type", "string")
                put("description", "Path to the file, relative to the workspace.")
            })
            put("content", kotlinx.serialization.json.buildJsonObject {
                put("type", "string")
                put("description", "UTF-8 text content to write to the file.")
            })
        })
        put("required", kotlinx.serialization.json.buildJsonObject { put("0", "path"); put("1", "content") })
    }

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val path = input["path"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: path")
        val content = input["content"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: content")

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(BuiltInToolExecutionError.WORKSPACE_VIOLATION, e.message ?: "Path rejected by workspace validator")
        }

        return try {
            Files.createDirectories(target.parent ?: context.workspace)
            Files.writeString(target, content, Charsets.UTF_8)
            BuiltInToolExecutionResult(output = "Wrote ${content.length} bytes to $path")
        } catch (e: Exception) {
            errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to write file: ${e.message}")
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}

