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
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

/**
 * Lists the contents of a directory inside the worker workspace.
 *
 * Output is plain text with `[FILE]`/`[DIR]` prefixes (matching the spec). When `includeSizes` is
 * true, the size in bytes is appended to each entry.
 */
class ListDirectoryTool : BuiltInTool {
    override val name: String = "list_directory"
    override val description: String = "List the contents of a directory with [FILE]/[DIR] prefixes."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Directory path relative to the workspace (defaults to the workspace root).")
            })
            put("sortBy", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonObject { put("0", "name"); put("1", "size") })
                put("description", "Sort entries by name (default) or size.")
            })
            put("includeSizes", buildJsonObject {
                put("type", "boolean")
                put("description", "Include file sizes in the listing.")
            })
            put("recursive", buildJsonObject {
                put("type", "boolean")
                put("description", "Recursively list subdirectories with indentation.")
            })
        })
    }

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val path = input["path"]?.jsonPrimitive?.content ?: "."
        val sortBy = input["sortBy"]?.jsonPrimitive?.content ?: "name"
        val includeSizes = input["includeSizes"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val recursive = input["recursive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (sortBy !in setOf("name", "size")) {
            return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Invalid 'sortBy' value: $sortBy")
        }

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(BuiltInToolExecutionError.WORKSPACE_VIOLATION, e.message ?: "Path rejected by workspace validator")
        }

        if (!Files.exists(root) || !root.isDirectory()) {
            return errorResult(BuiltInToolExecutionError.NOT_FOUND, "Directory not found: $path")
        }

        val listing = if (recursive) {
            renderRecursive(root, includeSizes, sortBy)
        } else {
            renderFlat(root, includeSizes, sortBy)
        }

        return BuiltInToolExecutionResult(output = listing)
    }

    private fun renderFlat(root: Path, includeSizes: Boolean, sortBy: String): String {
        val entries = Files.list(root).use { stream -> stream.toList() }
        val sorted = sortEntries(entries, sortBy)
        return buildString {
            sorted.forEach { entry ->
                append(if (entry.isDirectory()) "[DIR] " else "[FILE] ")
                append(entry.fileName.toString())
                if (includeSizes && entry.isRegularFile()) {
                    append("  (${Files.size(entry)} bytes)")
                }
                append('\n')
            }
        }.trimEnd()
    }

    private fun renderRecursive(root: Path, includeSizes: Boolean, sortBy: String): String {
        val sb = StringBuilder()
        renderRecursiveInto(root, 0, includeSizes, sortBy, sb)
        return sb.toString().trimEnd()
    }

    private fun renderRecursiveInto(dir: Path, depth: Int, includeSizes: Boolean, sortBy: String, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        Files.list(dir).use { stream ->
            val entries = sortEntries(stream.toList(), sortBy)
            entries.forEach { entry ->
                sb.append(indent)
                sb.append(if (entry.isDirectory()) "[DIR] " else "[FILE] ")
                sb.append(entry.fileName.toString())
                if (includeSizes && entry.isRegularFile()) {
                    sb.append("  (${Files.size(entry)} bytes)")
                }
                sb.append('\n')
                if (entry.isDirectory()) {
                    renderRecursiveInto(entry, depth + 1, includeSizes, sortBy, sb)
                }
            }
        }
    }

    private fun sortEntries(entries: List<Path>, sortBy: String): List<Path> {
        return if (sortBy == "size") {
            entries.sortedBy { if (it.isRegularFile()) Files.size(it) else -1L }
        } else {
            entries.sortedBy { it.fileName.toString().lowercase() }
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}

