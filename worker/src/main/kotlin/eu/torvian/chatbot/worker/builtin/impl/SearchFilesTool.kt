package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * Searches for files and directories whose path — relative to the starting directory — matches a glob pattern.
 *
 * The walk over the starting directory is recursive, but the glob pattern itself is matched against
 * each candidate's path relative to the starting directory (the workspace root combined with the
 * requested `path`), so patterns behave intuitively regardless of the absolute filesystem location
 * (e.g. `**.kt` or a bare `*.kt`). A bare `*.kt` only matches entries directly in the starting
 * directory; use `**` (e.g. `**.kt`) for recursive matching that also includes the starting directory
 * itself. A pattern can also be anchored to a subdirectory — for instance a pattern that starts with
 * `src/` and ends in `**` matches the entire src subtree. Supports exclude patterns (string or array)
 * that skip matched paths. Returns a newline-separated list of matched paths (relative to the starting
 * directory).
 */
class SearchFilesTool : BuiltInTool {
    override val name: String = "search_files"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path", "pattern", "excludePatterns")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val pattern = parseRequiredString(input, "pattern", validationErrors)
        val path = parseOptionalString(input, "path", validationErrors) ?: "."
        val exclude = parseStringOrStringArray(input, "excludePatterns", validationErrors)

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
            if (!Files.exists(root)) {
                return@withContext builtInToolErrorResult(
                    BuiltInToolExecutionError.NOT_FOUND,
                    "Starting path not found: $path"
                )
            }

            val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val excludeMatchers: List<PathMatcher> = exclude.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
            val matches = mutableListOf<String>()

            val candidates: List<Path> = Files.walk(root).use { stream -> stream.toList() }
            candidates.forEach { candidate ->
                // Relativize once against the starting directory (root) and reuse for both matching and
                // the final output, so glob patterns behave intuitively (e.g. "**.kt" or "src/**.kt")
                // regardless of the absolute filesystem location. The relative path is also exactly what
                // we return to the caller, so a search rooted at "website/css" with pattern "*" matches
                // "style.css" rather than requiring the full "website/css/style.css". Normalize to
                // forward slashes so output and matching are platform-independent (glob matchers expect
                // '/' separators), keeping results consistent across operating systems.
                val relativeCandidate = root.relativize(candidate).toString().replace('\\', '/')
                if (excludeMatchers.any { it.matches(Path.of(relativeCandidate)) }) return@forEach
                if (matcher.matches(Path.of(relativeCandidate))) {
                    matches.add(relativeCandidate)
                }
            }

            val hint =
                if (matches.isEmpty() && Files.isDirectory(root) && pattern != null
                    && looksLikeNonRecursiveTopLevelPattern(pattern) && hasSubdirectories(root)
                ) {
                    val suggested = toRecursiveHintPattern(pattern)
                    "\n\nHint: '$pattern' only matches entries in the starting directory. If you intended a recursive search, use '$suggested'."
                } else {
                    ""
                }

            BuiltInToolExecutionResult(
                output = if (matches.isEmpty()) {
                    if (hint.isNotEmpty()) "No matches found$hint" else ""
                } else {
                    matches.joinToString(separator = "\n")
                },
            )
        }
    }

}