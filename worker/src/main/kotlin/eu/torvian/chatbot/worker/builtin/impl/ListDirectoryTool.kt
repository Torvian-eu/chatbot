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
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalInt
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalString
import eu.torvian.chatbot.worker.builtin.validation.parseRequiredString
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
        val validKeys = setOf("path", "sortBy", "includeSizes", "recursive", "maxEntries")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val sortBy = parseOptionalString(input, "sortBy", validationErrors) ?: "name"
        if (sortBy !in setOf("name", "size")) {
            validationErrors.add("Invalid 'sortBy' value: $sortBy (expected 'name' or 'size')")
        }

        val path = parseRequiredString(input, "path", validationErrors)
        val includeSizes = parseOptionalBoolean(input, "includeSizes", defaultValue = false, validationErrors)
        val recursive = parseOptionalBoolean(input, "recursive", defaultValue = false, validationErrors)
        val maxEntries = parseOptionalInt(input, "maxEntries", defaultValue = 25, validationErrors)
        if (maxEntries < 1) {
            validationErrors.add("Argument 'maxEntries' must be >= 1")
        }

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
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

            val (listing, truncated) = if (recursive) {
                renderRecursive(root, includeSizes, sortBy, maxEntries)
            } else {
                renderFlat(root, includeSizes, sortBy, maxEntries)
            }

            val summary = if (truncated) "\n\nShowing first $maxEntries entries (truncated)." else ""
            val output = if (listing.isEmpty() && !truncated) "" else listing + summary

            val details = buildJsonObject {
                put("truncated", truncated)
            }

            BuiltInToolExecutionResult(
                output = output,
                details = details,
            )
        }
    }

    private fun renderFlat(root: Path, includeSizes: Boolean, sortBy: String, maxEntries: Int): Pair<String, Boolean> {
        val entries = Files.list(root).use { stream -> stream.toList() }
        val sorted = sortEntries(entries, sortBy)
        val limited = sorted.take(maxEntries)
        val truncated = sorted.size > maxEntries
        val listing = buildString {
            limited.forEach { entry ->
                append(if (entry.isDirectory()) "[DIR] " else "[FILE] ")
                append(entry.fileName.toString())
                if (includeSizes && entry.isRegularFile()) {
                    append("  (${Files.size(entry)} bytes)")
                }
                append('\n')
            }
        }.trimEnd()
        return listing to truncated
    }

    private fun renderRecursive(root: Path, includeSizes: Boolean, sortBy: String, maxEntries: Int): Pair<String, Boolean> {
        val sb = StringBuilder()
        var count = 0
        var truncated = false
        fun renderRecursiveInto(dir: Path, depth: Int) {
            if (truncated) return
            val indent = "  ".repeat(depth)
            Files.list(dir).use { stream ->
                val entries = sortEntries(stream.toList(), sortBy)
                for (entry in entries) {
                    if (count >= maxEntries) {
                        truncated = true
                        return
                    }
                    count++
                    sb.append(indent)
                    sb.append(if (entry.isDirectory()) "[DIR] " else "[FILE] ")
                    sb.append(entry.fileName.toString())
                    if (includeSizes && entry.isRegularFile()) {
                        sb.append("  (${Files.size(entry)} bytes)")
                    }
                    sb.append('\n')
                    if (entry.isDirectory()) {
                        renderRecursiveInto(entry, depth + 1)
                    }
                }
            }
        }
        renderRecursiveInto(root, 0)
        return sb.toString().trimEnd() to truncated
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