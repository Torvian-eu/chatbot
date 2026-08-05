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
import kotlin.test.assertFalse
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
     * @param maxResults Optional maximum number of results.
     * @param caseSensitive Optional boolean controlling case-sensitive matching.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        pattern: String,
        path: String = ".",
        excludePatterns: Any? = null,
        maxResults: Any? = null,
        caseSensitive: Any? = null,
    ): JsonObject = buildJsonObject {
        put("pattern", pattern)
        put("path", path)
        when (excludePatterns) {
            is String -> put("excludePatterns", excludePatterns)
            is List<*> -> putJsonArray("excludePatterns") {
                for (p in excludePatterns) add(p as String)
            }
        }
        when (maxResults) {
            is Int -> put("maxResults", maxResults)
            is String -> put("maxResults", maxResults)
            is JsonElement -> put("maxResults", maxResults)
        }
        when (caseSensitive) {
            is Boolean -> put("caseSensitive", caseSensitive)
            is String -> put("caseSensitive", caseSensitive)
            is Int -> put("caseSensitive", caseSensitive)
            is JsonElement -> put("caseSensitive", caseSensitive)
        }
    }

    /**
     * Creates an execution context rooted at [workspace] using the IO dispatcher.
     */
    private fun context(workspace: Path): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            defaultSearchTimeoutSeconds = 5,
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

            val output = assertSuccess(result)
            val matches = result.details?.jsonObject?.get("matches")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            assertEquals(setOf("sub/b.kt"), matches)
            assertTrue(output.contains("Hint: '**/*.kt' excludes files directly in the starting directory"))
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

    @Test
    fun `no matches with non-recursive pattern and subdirectories shows hint for star pattern`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt"), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("No matches found"))
            assertTrue(output.contains("Hint: '*.kt' only matches entries in the starting directory. If you intended a recursive search, use '**.kt'."))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no matches with non-recursive pattern and subdirectories shows hint for plain filename`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/README.md").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("README.md"), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("No matches found"))
            assertTrue(output.contains("Hint: 'README.md' only matches entries in the starting directory. If you intended a recursive search, use '**README.md'."))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no matches with double star pattern shows no hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.java"), context(dir))

            val output = assertSuccess(result)
            assertEquals("", output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no matches with path-aware pattern shows no hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src/Main.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("src/*.java"), context(dir))

            val output = assertSuccess(result)
            assertEquals("", output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `matches found in top directory shows no hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt"), context(dir))

            val output = assertSuccess(result)
            assertEquals("a.kt", output.replace('\\', '/'))
            assertFalse(output.contains("Hint:"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no matches in flat directory with no subdirectories shows no hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.txt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("*.kt"), context(dir))

            val output = assertSuccess(result)
            assertEquals("", output)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit smaller maxResults truncates results and shows truncation note and details`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("b.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("c.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.kt", maxResults = 2), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("result(s) shown — truncated to 2 result(s)"), "expected truncation notice; got:\n$output")
            val details = result.details
            assertEquals(true, details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit maxResults larger than available results does not truncate`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.kt", maxResults = 10), context(dir))

            val output = assertSuccess(result)
            assertTrue(!output.contains("truncated"), "did not expect truncation notice; got:\n$output")
            val details = result.details
            assertEquals(false, details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid maxResults returns INVALID_INPUT`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val resultZero = tool.execute(buildInput("**.kt", maxResults = 0), context(dir))
            assertError(resultZero, BuiltInToolExecutionError.INVALID_INPUT)

            val resultNegative = tool.execute(buildInput("**.kt", maxResults = -1), context(dir))
            assertError(resultNegative, BuiltInToolExecutionError.INVALID_INPUT)

            val resultInvalidString = tool.execute(buildInput("**.kt", maxResults = "abc"), context(dir))
            assertError(resultInvalidString, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `omitted maxResults defaults to 25 and truncates when matches exceed 25`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            for (i in 1..26) {
                dir.resolve("file$i.kt").writeText("x", Charsets.UTF_8)
            }

            val result = tool.execute(buildInput("**.kt"), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("result(s) shown — truncated to 25 result(s)"), "expected truncation notice; got:\n$output")
            val details = result.details
            assertEquals(true, details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `leading slash star star pattern shows warning hint even when matches found and suggests fix without triple stars`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)
            val sub = dir.resolve("sub").toFile()
            sub.mkdirs()
            dir.resolve("sub/b.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**/*.kt"), context(dir))
            val output = assertSuccess(result)
            assertTrue(output.contains("Hint: '**/*.kt' excludes files directly in the starting directory"), "output=$output")
            assertTrue(output.contains("use '**.kt'"), "output=$output")
            assertFalse(output.contains("***"), "output=$output")
            // Also verify that top-level file a.kt was excluded by **/*.kt (glob semantics unchanged)
            assertFalse(output.contains("a.kt"), "output=$output")
            assertTrue(output.contains("sub/b.kt"), "output=$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `leading slash star star pattern like README md shows warning hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            val sub = dir.resolve("sub").toFile()
            sub.mkdirs()
            dir.resolve("sub/README.md").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**/README.md"), context(dir))
            val output = assertSuccess(result)
            assertTrue(output.contains("Hint: '**/README.md' excludes files directly in the starting directory"), "output=$output")
            assertTrue(output.contains("use '**README.md'"), "output=$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pattern starting with double star without slash does not show leading slash warning hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("**.kt"), context(dir))
            val output = assertSuccess(result)
            assertFalse(output.contains("excludes files directly in the starting directory"), "output=$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `intentional or nested slash star star patterns do not show leading slash warning hint`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src/a.kt").writeText("x", Charsets.UTF_8)

            for (p in listOf("**/**.kt", "**/src/*.kt", "**/*/*.kt")) {
                val result = tool.execute(buildInput(p), context(dir))
                val output = assertSuccess(result)
                assertFalse(output.contains("excludes files directly in the starting directory"), "pattern $p should not trigger hint; output=$output")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Case-sensitivity scenarios
    // -----------------------------------------------------------------------------------------

    @Test
    fun `case-insensitive default matches differing case files`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("Main.KT").writeText("x", Charsets.UTF_8)
            dir.resolve("README.MD").writeText("x", Charsets.UTF_8)

            // Default (caseSensitive=false) matches both regardless of pattern case.
            val result = tool.execute(buildInput("*.kt"), context(dir))
            val matchesKt = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("Main.KT"), matchesKt)

            val resultMd = tool.execute(buildInput("*.md"), context(dir))
            val matchesMd = assertSuccess(resultMd).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("README.MD"), matchesMd)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-insensitive pattern with mixed case matches`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("HelloWorld.kt").writeText("x", Charsets.UTF_8)

            // Pattern with mixed case still matches the differing-case file by default.
            val result = tool.execute(buildInput("helloworld.*"), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("HelloWorld.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-insensitive nested pattern matches differing case files`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("src").toFile().mkdirs()
            dir.resolve("src/UTILS").toFile().mkdirs()
            dir.resolve("src/UTILS/Helper.KT").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput("src/utils/**helper.*"), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("src/UTILS/Helper.KT"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-sensitive matching does not fold case`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("main.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("Main.KT").writeText("x", Charsets.UTF_8)

            // caseSensitive=true: pattern "*.kt" matches only the exact-case "main.kt".
            val result = tool.execute(buildInput("*.kt", caseSensitive = true), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("main.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-insensitive exclude pattern filters differing case files`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("keep.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("SKIP.kt").writeText("x", Charsets.UTF_8)

            // Default (caseSensitive=false): exclude "skip.kt" also excludes "SKIP.kt".
            val result = tool.execute(buildInput("**.kt", excludePatterns = listOf("skip.kt")), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("keep.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-sensitive exclude pattern does not fold case`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("keep.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("SKIP.kt").writeText("x", Charsets.UTF_8)
            dir.resolve("skip.kt").writeText("x", Charsets.UTF_8)

            // caseSensitive=true: exclude "skip.kt" only excludes the exact-case "skip.kt", not "SKIP.kt".
            val result = tool.execute(buildInput("**.kt", excludePatterns = listOf("skip.kt"), caseSensitive = true), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("keep.kt", "SKIP.kt"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `original case preserved in output when matching case-insensitively`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("HelloWorld.KT").writeText("x", Charsets.UTF_8)

            // Case-insensitive matching returns the original-case path.
            val result = tool.execute(buildInput("*.kt"), context(dir))
            val output = assertSuccess(result)
            assertTrue(output.contains("HelloWorld.KT"), "expected original-case path in output; got: $output")
            assertFalse(output.contains("helloworld.kt"), "did not expect lowercased path in output; got: $output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid caseSensitive value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)

            val resultString = tool.execute(buildInput("**.kt", caseSensitive = "maybe"), context(dir))
            assertError(resultString, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(
                resultString.errorDetails!!.contains("Argument 'caseSensitive' must be a boolean"),
                "expected caseSensitive type error; got: ${resultString.errorDetails}"
            )

            val resultNumber = tool.execute(buildInput("**.kt", caseSensitive = 1), context(dir))
            assertError(resultNumber, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown parameter caseSensitive was previously rejected but now accepted`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("a.kt").writeText("x", Charsets.UTF_8)

            // "caseSensitive" must be a known/valid key, not flagged as an unknown parameter.
            val result = tool.execute(buildJsonObject {
                put("pattern", "**.kt")
                put("path", ".")
                put("caseSensitive", true)
            }, context(dir))
            val output = assertSuccess(result)
            assertFalse(output.contains("Unknown parameter"), "caseSensitive should be a valid key; output=$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-insensitive union pattern matches differing case`() = runTest {
        val dir = createTempDirectory("search-files-test")
        try {
            dir.resolve("photo.JPG").writeText("x", Charsets.UTF_8)
            dir.resolve("video.mov").writeText("x", Charsets.UTF_8)

            // Union pattern {jpg,png} with case-insensitive default matches "photo.JPG".
            val result = tool.execute(buildInput("*.{jpg,png}"), context(dir))
            val matches = assertSuccess(result).lines().map { it.replace('\\', '/') }.toSet()
            assertEquals(setOf("photo.JPG"), matches)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
