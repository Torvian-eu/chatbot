package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [SearchTextTool].
 *
 * These tests lock down the core search semantics: plain/regex matching, case sensitivity,
 * whole-word anchoring, file/relative-path filtering, context lines, result truncation,
 * binary file handling, and workspace containment. They do not cover input validation,
 * error handling, or hint behavior which are tested in separate focused test classes.
 */
class SearchTextToolScenariosTest {

    private val tool = SearchTextTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [SearchTextTool.execute].
     *
     * @param query Required search query or regex pattern.
     * @param path Workspace-relative starting path (defaults to "." when null).
     * @param mode `"plain"` or `"regex"` (defaults to `"regex"` when null).
     * @param caseSensitive When true, matching is case-sensitive.
     * @param wholeWord When true, matches are anchored to word boundaries (plain mode only).
     * @param filePattern Optional glob filter applied to file names.
     * @param excludePatterns Optional glob filters applied to relative paths.
     * @param contextBefore Number of context lines to include before each match.
     * @param contextAfter Number of context lines to include after each match.
     * @param maxResults Maximum number of matches to return.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        query: String,
        path: String? = null,
        mode: String? = null,
        caseSensitive: Boolean? = null,
        wholeWord: Boolean? = null,
        filePattern: String? = null,
        excludePatterns: List<String>? = null,
        contextBefore: Int? = null,
        contextAfter: Int? = null,
        maxResults: Int? = null,
    ): JsonObject = buildJsonObject {
        put("query", query)
        if (path != null) put("path", path)
        if (mode != null) put("mode", mode)
        if (caseSensitive != null) put("caseSensitive", caseSensitive)
        if (wholeWord != null) put("wholeWord", wholeWord)
        if (filePattern != null) put("filePattern", filePattern)
        if (excludePatterns != null) putJsonArray("excludePatterns") { for (p in excludePatterns) add(p) }
        if (contextBefore != null) put("contextBefore", contextBefore)
        if (contextAfter != null) put("contextAfter", contextAfter)
        if (maxResults != null) put("maxResults", maxResults)
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
     * Asserts that [result] is a successful (non-error) result and returns it for further inspection.
     */
    private fun assertSuccess(result: BuiltInToolExecutionResult): BuiltInToolExecutionResult {
        assertFalse(result.isError, "Expected success but got error: ${result.errorMessage}")
        return result
    }

    // -----------------------------------------------------------------------------------------
    // Core search scenarios
    // -----------------------------------------------------------------------------------------

    @Test
    fun `plain search finds a line in a single file`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("alpha\nbeta\ngamma", Charsets.UTF_8)

            val result = tool.execute(buildInput("beta", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertTrue(success.output!!.contains("=== file: sample.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("2: beta"), "output=${success.output}")
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recursive directory search finds matches across files`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("a.txt").writeText("needle", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/b.txt").writeText("haystack\nneedle here", Charsets.UTF_8)

            val result = tool.execute(buildInput("needle", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("=== file: a.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("1: needle"), "output=${success.output}")
            assertTrue(success.output!!.contains("=== file: sub/b.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("2: needle here"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-insensitive search matches regardless of case by default`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("Hello World", Charsets.UTF_8)

            val result = tool.execute(buildInput("hello"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `case-sensitive search does not match differing case`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("Hello World", Charsets.UTF_8)

            val result = tool.execute(buildInput("hello", caseSensitive = true), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("No matches found"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `regex search matches a pattern`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("order 123\norder 456\nnote 789", Charsets.UTF_8)

            val result = tool.execute(buildInput("order \\d+", mode = "regex"), context(dir))

            val success = assertSuccess(result)
            assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `whole-word search anchors matches to word boundaries`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("cat\ncatalog\ncategory", Charsets.UTF_8)

            val result = tool.execute(buildInput("cat", mode = "plain", wholeWord = true), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("=== file: sample.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("1: cat"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `filePattern filters candidate files by name`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("keep.md").writeText("token", Charsets.UTF_8)
            dir.resolve("skip.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(buildInput("token", mode = "plain", filePattern = "*.md"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("keep.md"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludePatterns apply to relative paths not just filenames`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("keep.txt").writeText("token", Charsets.UTF_8)
            dir.resolve("vendor").toFile().mkdirs()
            dir.resolve("vendor/skip.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(buildInput("token", mode = "plain", excludePatterns = listOf("vendor/*")), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("keep.txt"))
            assertFalse(success.output!!.contains("vendor/skip.txt"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `contextBefore and contextAfter include surrounding lines`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("line1\nline2\nMATCH\nline4\nline5", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("MATCH", contextBefore = 1, contextAfter = 1),
                context(dir)
            )

            val success = assertSuccess(result)
            val details = success.details!!
            val match = details["matches"]!!.jsonArray.first().jsonObject
            assertEquals(listOf("line2"), match["before"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("line4"), match["after"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(success.output!!.contains("=== file: sample.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("2: line2"), "output=${success.output}")
            assertTrue(success.output!!.contains("4: line4"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CRLF files are handled without stray carriage returns`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").toFile().writeText("alpha\r\nbeta\r\ngamma\r\n", Charsets.UTF_8)

            val result = tool.execute(buildInput("beta"), context(dir))

            val success = assertSuccess(result)
            assertTrue(success.output!!.contains("=== file: sample.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("2: beta"), "output=${success.output}")
            assertFalse(success.output!!.contains("\r"), "output must not contain carriage returns: ${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readable output orders context-before before the matching line`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("line1\nline2\nMATCH\nline4\nline5", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("MATCH", contextBefore = 2, contextAfter = 1),
                context(dir)
            )

            val success = assertSuccess(result)
            val lines = success.output!!.lines()
            val headerIdx = lines.indexOfFirst { it == "=== file: sample.txt ===" }
            assertTrue(headerIdx >= 0, "header not found in output: ${success.output}")
            val matchIdx = lines.indexOfFirst { it == "3: MATCH" }
            assertTrue(matchIdx > headerIdx, "matching line not found after header: ${success.output}")
            assertEquals("1: line1", lines[matchIdx - 2])
            assertEquals("2: line2", lines[matchIdx - 1])
            assertEquals("4: line4", lines[matchIdx + 1])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple occurrences on one line count as a single line-based result`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("cat cat cat\ncatalog", Charsets.UTF_8)

            val result = tool.execute(buildInput("cat", mode = "plain", wholeWord = true), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            val lines = success.output!!.lines()
            assertEquals("=== file: sample.txt ===", lines.first())
            assertEquals("1: cat cat cat", lines[1])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `maxResults truncates the total returned matches`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("a\nb\nc\nd\ne", Charsets.UTF_8)

            val result = tool.execute(buildInput("[a-e]", mode = "regex", maxResults = 2), context(dir))

            val success = assertSuccess(result)
            val details = success.details!!
            assertEquals(2, details["totalMatches"]?.jsonPrimitive?.int)
            assertEquals(true, details["truncated"]?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `binary or unreadable files are skipped without failing the search`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("binary.bin").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            dir.resolve("text.txt").writeText("needle", Charsets.UTF_8)

            val result = tool.execute(buildInput("needle"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertEquals(1, success.details!!["skippedFiles"]?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `starting from a subdirectory reports paths relative to that subdirectory`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("top.txt").writeText("token", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/nested.txt").writeText("token", Charsets.UTF_8)
            dir.resolve("sub/deep").toFile().mkdirs()
            dir.resolve("sub/deep/leaf.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(buildInput("token", path = "sub"), context(dir))

            val success = assertSuccess(result)
            assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("=== file: nested.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("=== file: deep/leaf.txt ==="), "output=${success.output}")
            assertTrue(success.output!!.contains("1: token"), "output=${success.output}")
            assertFalse(success.output!!.contains("sub/"), "paths must be relative to the starting directory")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludePatterns match against the path relative to the starting directory`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/keep.txt").writeText("token", Charsets.UTF_8)
            dir.resolve("sub/vendor").toFile().mkdirs()
            dir.resolve("sub/vendor/skip.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("token", path = "sub", excludePatterns = listOf("vendor/*")),
                context(dir)
            )

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("keep.txt"))
            assertFalse(success.output!!.contains("vendor/skip.txt"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `filePattern matches the path relative to the starting directory`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/keep.md").writeText("token", Charsets.UTF_8)
            dir.resolve("sub/skip.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(buildInput("token", path = "sub", filePattern = "*.md"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("keep.md"))
            assertFalse(success.output!!.contains("skip.txt"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludePatterns accepts a single string glob`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("keep.txt").writeText("token", Charsets.UTF_8)
            dir.resolve("vendor").toFile().mkdirs()
            dir.resolve("vendor/skip.txt").writeText("token", Charsets.UTF_8)

            val result = tool.execute(
                buildJsonObject {
                    put("query", "token")
                    put("excludePatterns", "vendor/*")
                },
                context(dir)
            )

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("keep.txt"))
            assertFalse(success.output!!.contains("vendor/skip.txt"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `regex mode is now the default`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("order 123\norder 456\nnote 789", Charsets.UTF_8)

            // Without specifying mode, regex is now the default
            val result = tool.execute(buildInput("order \\d+"), context(dir))

            val success = assertSuccess(result)
            assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `omitted maxResults defaults to 50 and truncates when matches exceed 50`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val lines = (1..60).joinToString("\n") { "match $it" }
            dir.resolve("sample.txt").writeText(lines, Charsets.UTF_8)

            val result = tool.execute(buildInput("match"), context(dir))

            val success = assertSuccess(result)
            val details = success.details!!
            assertEquals(50, details["totalMatches"]?.jsonPrimitive?.int)
            assertEquals(true, details["truncated"]?.jsonPrimitive?.boolean)
            assertTrue(success.output!!.contains("truncated to 50 result(s)"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}