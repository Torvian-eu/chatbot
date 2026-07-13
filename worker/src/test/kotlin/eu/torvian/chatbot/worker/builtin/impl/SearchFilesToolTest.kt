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
 * These tests lock down the current intended semantics: glob patterns are matched against each
 * candidate path **relative to the starting directory** (the workspace root combined with the
 * requested `path`), and matched paths are returned **relative to that same starting directory**.
 * A bare `*.kt` matches only the starting directory, while `**.kt` (or '**&#47;*.kt' for
 * subdirectories) recurses. Exclude-pattern filtering, starting-directory-relative newline-separated
 * output, missing-start handling, and workspace containment are also covered. They do not redesign
 * the tool.
 */
class SearchFilesToolTest {

    private val tool = SearchFilesTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [SearchFilesTool.execute].
     *
     * @param pattern Glob pattern matched against the workspace-relative path. Use `**` for recursive matching.
     * @param path Workspace-relative starting directory (defaults to "." when null).
     * @param excludePatterns Optional glob pattern(s) to exclude (string or list).
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        pattern: String,
        path: String? = null,
        excludePatterns: Any? = null,
    ): JsonObject = buildJsonObject {
        put("pattern", pattern)
        if (path != null) put("path", path)
        when (excludePatterns) {
            is String -> put("excludePatterns", excludePatterns)
            is List<*> -> putJsonArray("excludePatterns") {
                for (p in excludePatterns) add(p as String)
            }
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
    fun `bare pattern matches only the starting directory non-recursively`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub/c.txt").writeText("x", Charsets.UTF_8)

            // `*.kt` matches only entries directly in the starting directory (relative path "a.kt").
            val result = tool.execute(buildInput("*.kt"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("a.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `double star pattern finds matching files recursively including root`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub/c.txt").writeText("x", Charsets.UTF_8)

            // `**.kt` recurses from the starting directory and also matches root-level files.
            val result = tool.execute(buildInput("**.kt"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("a.kt", "sub/b.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `double star slash pattern finds nested files but not root`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)

            // `**/*.kt` matches files in subdirectories only (the root file "a.kt" has no slash).
            val result = tool.execute(buildInput("**/*.kt"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("sub/b.kt"), matches)
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

            val result = tool.execute(buildInput("**.kt", excludePatterns = listOf("skip.kt")), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("keep.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exclude patterns as single string works`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("keep.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("skip.kt").writeText("x", Charsets.UTF_8)

            // Exclude patterns accept a single string (not just an array).
            val result = tool.execute(buildInput("**.kt", excludePatterns = "skip.kt"), context(dir))

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

            val result = tool.execute(buildInput("**.kt"), context(dir))

            val output = assertSuccess(result)
            // Exactly two entries, each relative to the starting directory (which is the workspace
            // root here, since no "path" was supplied).
            assertEquals(2, output.lines().size)
            assertTrue(output.lines().none { it.contains(File.separator + "..") }, "paths must be relative to the starting directory")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-existent starting directory returns not found`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val result = tool.execute(buildInput("**.kt", path = "missing"), context(dir))

            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val result = tool.execute(buildInput("**.kt", path = "../escaped"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pattern with directory prefix matches relative subtree`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src").resolve("Main.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("src").resolve("utils").toFile().mkdirs()
            dir.resolve("src").resolve("utils").resolve("Helper.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("test").toFile().mkdirs()
            dir.resolve("test").resolve("Main.kt").writeText("x", Charsets.UTF_8)

            // Relative patterns match the starting-directory-relative path. "src/**.kt" matches the
            // whole src subtree, including its direct child src/Main.kt (unlike "src/**/*.kt").
            val result = tool.execute(buildInput("src/**.kt"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("src/Main.kt", "src/utils/Helper.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple exclude patterns filter matches out of the result set`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("keep1.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("keep2.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("skip1.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("skip2.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.kt", excludePatterns = listOf("skip1.kt", "skip2.kt")), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("keep1.kt", "keep2.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `empty result returns empty string`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.txt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.kt"), context(dir))

            val output = assertSuccess(result)
            assertEquals("", output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `starting from subdirectory searches within that subtree`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("root.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub").resolve("nested").toFile().mkdirs()
            dir.resolve("sub").resolve("nested").resolve("deep.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").resolve("sibling.kt").writeText("x", Charsets.UTF_8)

            // Start from "sub"; relative paths are reported from the starting directory, so the
            // "sub/" prefix is dropped (e.g. "sub/sibling.kt" becomes "sibling.kt").
            val result = tool.execute(buildInput("**.kt", path = "sub"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("nested/deep.kt", "sibling.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bare pattern matches entries directly in a nested starting directory`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("website").toFile().mkdirs()
            dir.resolve("website").resolve("css").toFile().mkdirs()
            dir.resolve("website").resolve("css").resolve("style.css").writeText("x", Charsets.UTF_8)
            dir.resolve("website").resolve("css").resolve("theme.css").writeText("x", Charsets.UTF_8)
            dir.resolve("website").resolve("index.html").writeText("x", Charsets.UTF_8)

            // Starting at "website/css" with a bare "*" matches "style.css"/"theme.css" (relative to
            // the start), plus the starting directory itself. Output is reported relative to the
            // starting directory.
            val result = tool.execute(buildInput("*", path = "website/css"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("", "style.css", "theme.css"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pattern with directory path matches correctly`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src").resolve("Main.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("src").resolve("utils").toFile().mkdirs()
            dir.resolve("src").resolve("utils").resolve("Helper.kt").writeText("x", Charsets.UTF_8)

            // "src/utils/*.kt" matches only the direct children of src/utils.
            val result = tool.execute(buildInput("src/utils/*.kt"), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("src/utils/Helper.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing required pattern argument returns invalid input`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val result = tool.execute(buildJsonObject { }, context(dir))

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exclude pattern with directory prefix matches relative path`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src").resolve("Main.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("test").toFile().mkdirs()
            dir.resolve("test").resolve("Main.kt").writeText("x", Charsets.UTF_8)

            // Exclude "test/Main.kt" by its starting-directory-relative path.
            val result = tool.execute(buildInput("**.kt", excludePatterns = listOf("test/Main.kt")), context(dir))

            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("src/Main.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
