package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * Recursively searches for files and directories whose names match a glob pattern.
 *
 * Supports exclude patterns that skip whole subtrees. Returns a newline-separated list of matched
 * paths (relative to the workspace).
 */
class SearchFilesTool : BuiltInTool {
    override val name: String = "search_files"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val pattern = input["pattern"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: pattern")
        val path = input["path"]?.jsonPrimitive?.content ?: "."
        val exclude = (input["excludePatterns"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(root)) {
                return@withContext errorResult(BuiltInToolExecutionError.NOT_FOUND, "Starting path not found: $path")
            }

            val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val excludeMatchers: List<PathMatcher> = exclude.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
            val matches = mutableListOf<Path>()

            val candidates: List<Path> = Files.walk(root).use { stream -> stream.toList() }
            candidates.forEach { candidate ->
                if (excludeMatchers.any { it.matches(candidate.fileName) }) return@forEach
                if (matcher.matches(candidate.fileName)) {
                    matches.add(candidate)
                }
            }

            val relative = matches.map { context.workspace.relativize(it).toString() }
            BuiltInToolExecutionResult(
                output = if (relative.isEmpty()) "" else relative.joinToString(separator = "\n"),
            )
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}
