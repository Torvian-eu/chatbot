package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [SearchFilesTool].
 *
 * These tests lock down the current intended semantics: recursive glob matching, exclude-pattern
 * filtering, workspace-relative newline-separated output, missing-start handling, and workspace
 * containment. They do not redesign the tool.
 */
class SearchFilesToolTest {

    private val tool = SearchFilesTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [SearchFilesTool.execute].
     *
     * @param pattern Glob pattern matched against entry names.
     * @param path Workspace-relative starting directory (defaults to "." when null).
     * @param excludePatterns Optional list of glob patterns to exclude.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        pattern: String,
        path: String? = null,
        excludePatterns: List<String>? = null,
    ): JsonObject = buildJsonObject {
        put("pattern", pattern)
        if (path != null) put("path", path)
        if (excludePatterns != null) putJsonArray("excludePatterns") {
            for (p in excludePatterns) add(p)
        }
    }

    /**
     * Creates an execution context rooted at [workspace] using the IO dispatcher.
     */
    private fun context(workspace: Path): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            ioDispatcher = Dispatchers.IO,
        )

    /**
     * Asserts that [result] is a successful (non-error) result and returns its textual output.
     */
    private fun assertSuccess(result: BuiltInToolExecutionResult): String {
        assertTrue(!result.isError, "Expected success but got error: ${result.errorMessage}")
        return result.output ?: ""
    }

    /**
     * Asserts that [result] is an error result carrying the expected [code].
     */
    private fun assertError(result: BuiltInToolExecutionResult, code: String) {
        assertTrue(result.isError, "Expected error but got success: ${result.output}")
        assertEquals(code, result.errorCode, "Unexpected error code; message=${result.errorMessage}")
    }

    // -----------------------------------------------------------------------------------------
    // Scenarios
    // -----------------------------------------------------------------------------------------

    @Test
    fun `glob pattern finds matching files recursively`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub/c.txt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt"), context(dir))

            // The tool returns OS-specific separators; normalize to '/' for a stable assertion.
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("a.kt", "sub/b.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exclude patterns filter matches out of the result set`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("keep.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("skip.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt", excludePatterns = listOf("skip.kt")), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("keep.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paths are returned as workspace-relative newline separated list`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("one.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("two.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt"), context(dir))

            val output = assertSuccess(result)
            // Exactly two entries, each relative to the workspace root.
            assertEquals(2, output.lines().size)
            assertTrue(output.lines().none { it.contains(File.separator + "..") }, "paths must be workspace-relative")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-existent starting directory returns not found`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val result = tool.execute(buildInput("*.kt", path = "missing"), context(dir))

            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val result = tool.execute(buildInput("*.kt", path = "../escaped"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
