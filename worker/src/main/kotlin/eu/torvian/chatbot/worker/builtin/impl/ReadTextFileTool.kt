package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Reads the contents of a text file inside the worker workspace.
 *
 * Always interprets the file as UTF-8 regardless of extension. The optional `head`/`tail`
 * parameters return the first/last N lines; supplying both is rejected to avoid ambiguity.
 */
class ReadTextFileTool : BuiltInTool {
    override val name: String = "read_text_file"
    override val description: String = "Read the contents of a text file as UTF-8."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Path to the file, relative to the workspace.")
            })
            put("head", buildJsonObject {
                put("type", "integer")
                put("description", "Return only the first N lines. Mutually exclusive with 'tail'.")
            })
            put("tail", buildJsonObject {
                put("type", "integer")
                put("description", "Return only the last N lines. Mutually exclusive with 'head'.")
            })
        })
        put("required", buildJsonArray { add("path") })
    }

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull()
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: path")
        val head = input["head"]?.jsonPrimitive?.intOrNull()
        val tail = input["tail"]?.jsonPrimitive?.intOrNull()
        if (head != null && tail != null) {
            return errorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Arguments 'head' and 'tail' are mutually exclusive"
            )
        }

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            try {
                val allLines = Files.readAllLines(target, Charsets.UTF_8)

                val selected = when {
                    head != null -> allLines.take(head)
                    tail != null -> allLines.takeLast(tail)
                    else -> allLines
                }

                BuiltInToolExecutionResult(
                    output = selected.joinToString(separator = "\n"),
                )
            } catch (_: NoSuchFileException) {
                errorResult(BuiltInToolExecutionError.NOT_FOUND, "File not found: $path")
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to read file: ${e.message}")
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

    private fun JsonPrimitive.intOrNull(): Int? = content.toIntOrNull()

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}
