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
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalBoolean
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalString
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Lists the contents of a directory inside the worker workspace.
 *
 * Output is plain text with `[FILE]`/`[DIR]` prefixes (matching the spec). When `includeSizes` is
 * true, the size in bytes is appended to each entry.
 */
class ListDirectoryTool : BuiltInTool {
    override val name: String = "list_directory"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path", "sortBy", "includeSizes", "recursive")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val sortBy = parseOptionalString(input, "sortBy", validationErrors) ?: "name"
        if (sortBy !in setOf("name", "size")) {
            validationErrors.add("Invalid 'sortBy' value: $sortBy (expected 'name' or 'size')")
        }

        val path = parseOptionalString(input, "path", validationErrors) ?: "."
        val includeSizes = parseOptionalBoolean(input, "includeSizes", defaultValue = false, validationErrors)
        val recursive = parseOptionalBoolean(input, "recursive", defaultValue = false, validationErrors)

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(root) || !root.isDirectory()) {
                return@withContext builtInToolErrorResult(BuiltInToolExecutionError.NOT_FOUND, "Directory not found: $path")
            }

            val listing = if (recursive) {
                renderRecursive(root, includeSizes, sortBy)
            } else {
                renderFlat(root, includeSizes, sortBy)
            }

            BuiltInToolExecutionResult(output = listing)
        }
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
        // Directories are always listed before files (the universal convention on Windows, Linux,
        // and macOS), with the requested sort applied as a secondary key within each group.
        val comparator = compareBy<Path> { if (it.isDirectory()) 0 else 1 }.thenBy {
            if (sortBy == "size") {
                if (it.isRegularFile()) Files.size(it) else -1L
            } else {
                0L
            }
        }.thenBy { it.fileName.toString().lowercase() }
        return entries.sortedWith(comparator)
    }
}