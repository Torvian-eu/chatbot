package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [SearchTextTool] hint behavior and `looksLikeRegex` heuristic.
 *
 * These tests cover:
 * - When the regex hint is shown or suppressed in plain mode
 * - The `looksLikeRegex` heuristic for detecting regex-like patterns
 * - False positive/negative boundaries for bracket/parenthesis matching
 */
class SearchTextToolHintTest {

    private val tool = SearchTextTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

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

    private fun context(workspace: Path): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            ioDispatcher = Dispatchers.IO,
        )

    private fun assertSuccess(result: BuiltInToolExecutionResult): BuiltInToolExecutionResult {
        assertFalse(result.isError, "Expected success but got error: ${result.errorMessage}")
        return result
    }

    // -----------------------------------------------------------------------------------------
    // Hint behavior scenarios
    // -----------------------------------------------------------------------------------------

    @Test
    fun `hint is shown when no matches found with regex-like query in plain mode`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // Search with explicit plain mode for something that looks like regex but won't match
            val result = tool.execute(buildInput("hello\\d+world", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
            assertTrue(success.output!!.contains("mode='regex'"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no hint is shown when matches found with regex-like query`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello  world", Charsets.UTF_8) // two spaces

            // Search for something that looks like a regex and will match
            val result = tool.execute(buildInput("hello\\s+world"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown when explicitly in plain mode with regex-like query`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // Search with explicit plain mode - hint IS shown because query looks like regex
            val result = tool.execute(buildInput("hello\\s+world", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
            assertTrue(success.output!!.contains("mode='regex'"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // looksLikeRegex heuristic: bracket/parenthesis false positive boundaries
    // -----------------------------------------------------------------------------------------

    @Test
    fun `no hint for single bracket without pair`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // Single "[" without closing "]" should not trigger regex hint
            val result = tool.execute(buildInput("hello[", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no hint for single parenthesis without pair`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // Single "(" without closing ")" should not trigger regex hint
            val result = tool.execute(buildInput("hello(", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no hint for bracket with literal text content`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("array[0] found here", Charsets.UTF_8)

            // "array[0]" has brackets but no regex-specific content (no ranges, escapes, or negation)
            val result = tool.execute(buildInput("array[0]", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no hint for parenthesis with literal text content`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("function(a, b) called here", Charsets.UTF_8)

            // "function(a, b)" has parentheses but no regex-specific content
            val result = tool.execute(buildInput("function(a, b)", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // looksLikeRegex heuristic: true positive cases
    // -----------------------------------------------------------------------------------------

    @Test
    fun `hint is shown for character class with range`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "[a-z]" is a regex character class with a range
            val result = tool.execute(buildInput("[a-z]", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown for character class with escape sequence`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "[\\d]" contains a regex escape sequence inside a character class
            val result = tool.execute(buildInput("[\\d]", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown for negated character class`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "[^...]" is a negated character class - strong regex indicator
            val result = tool.execute(buildInput("[^a-z]", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown for quantifier plus following atom`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "ab+" has quantifier + following an atom
            val result = tool.execute(buildInput("ab+", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown for quantifier asterisk following atom`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "ab*" has quantifier * following an atom
            val result = tool.execute(buildInput("ab*", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hint is shown for quantifier question mark following atom`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // "ab?" has quantifier ? following an atom
            val result = tool.execute(buildInput("ab?", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertTrue(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no hint for plain text without regex patterns`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            // Plain text with no regex patterns should not trigger hint
            val result = tool.execute(buildInput("hello world", mode = "plain"), context(dir))

            val success = assertSuccess(result)
            assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
            assertFalse(success.output!!.contains("Hint:"), "output=${success.output}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}