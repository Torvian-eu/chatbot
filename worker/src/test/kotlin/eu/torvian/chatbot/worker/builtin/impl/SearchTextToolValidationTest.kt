package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
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
 * Tests for [SearchTextTool] input validation and error handling.
 *
 * These tests cover unknown parameter rejection, missing/blank queries,
 * invalid regex patterns, and workspace containment violations.
 */
class SearchTextToolValidationTest {

    private val tool = SearchTextTool()

    private fun buildInput(
        query: String? = null,
        path: String = ".",
        mode: String? = null,
        caseSensitive: Boolean? = null,
        wholeWord: Boolean? = null,
        filePattern: String? = null,
        excludePatterns: List<String>? = null,
        contextBefore: Int? = null,
        contextAfter: Int? = null,
        maxResults: Int? = null,
    ): JsonObject = buildJsonObject {
        if (query != null) put("query", query)
        put("path", path)
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
            defaultSearchTimeoutSeconds = 5,
            ioDispatcher = Dispatchers.IO,
        )

    private fun assertSuccess(result: BuiltInToolExecutionResult): BuiltInToolExecutionResult {
        assertFalse(result.isError, "Expected success but got error: ${result.errorMessage}")
        return result
    }

    private fun assertError(result: BuiltInToolExecutionResult, code: String) {
        assertTrue(result.isError, "Expected error but got success: ${result.output}")
        assertEquals(code, result.errorCode, "Unexpected error code; message=${result.errorMessage}")
    }

    @Test
    fun `missing required query is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildJsonObject { put("path", ".") }, context(dir))
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blank query is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildInput("   "), context(dir))
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid regex is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildInput("(unclosed", mode = "regex"), context(dir))
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `wholeWord in regex mode is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildInput("cat", mode = "regex", wholeWord = true), context(dir))
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildInput("x", path = "../escaped"), context(dir))
            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
    @Test
    fun `non-existent starting path returns not found`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(buildInput("x", path = "missing"), context(dir))
            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------
    // Malformed value validation tests
    // ---------------------------------------------------------------------------------

    @Test
    fun `invalid caseSensitive value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("caseSensitive", "maybe")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'caseSensitive' must be a boolean"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid wholeWord value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("wholeWord", "notabool")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'wholeWord' must be a boolean"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid contextBefore value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("contextBefore", "abc")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'contextBefore' must be an integer"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid contextAfter value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("contextAfter", "abc")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'contextAfter' must be an integer"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid maxResults value is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("maxResults", "notanint")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'maxResults' must be an integer"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `query array is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("path", ".")
                    putJsonArray("query") { add("hello") }
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'query' must be a string"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path object is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", buildJsonObject { put("nested", "value") })
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'path' must be a string"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mode array is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    putJsonArray("mode") { add("plain") }
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'mode' must be a string"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `filePattern object is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("filePattern", buildJsonObject { put("nested", "value") })
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'filePattern' must be a string"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludePatterns object is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("excludePatterns", buildJsonObject { put("nested", "value") })
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'excludePatterns' must be a string or array of strings"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludePatterns array with a non-string element is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    putJsonArray("excludePatterns") {
                        add("*.tmp")
                        add(buildJsonObject { put("nested", "value") })
                    }
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'excludePatterns[1]' must be a string"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `caseSensitive array is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    putJsonArray("caseSensitive") { add(true) }
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'caseSensitive' must be a boolean"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `contextBefore array and contextAfter object are rejected together as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    putJsonArray("contextBefore") { add(1) }
                    put("contextAfter", buildJsonObject { put("nested", "value") })
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Argument 'contextBefore' must be an integer"))
            assertTrue(result.errorDetails!!.contains("Argument 'contextAfter' must be an integer"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple malformed fields accumulate validation errors`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    putJsonArray("query") { add("hello") }
                    put("path", buildJsonObject { put("nested", "value") })
                    putJsonArray("mode") { add("plain") }
                    put("filePattern", buildJsonObject { put("nested", "value") })
                    put("excludePatterns", buildJsonObject { put("nested", "value") })
                    putJsonArray("caseSensitive") { add(true) }
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val details = result.errorDetails!!
            assertTrue(details.contains("Argument 'query' must be a string"))
            assertTrue(details.contains("Argument 'path' must be a string"))
            assertTrue(details.contains("Argument 'mode' must be a string"))
            assertTrue(details.contains("Argument 'filePattern' must be a string"))
            assertTrue(details.contains("Argument 'excludePatterns' must be a string or array of strings"))
            assertTrue(details.contains("Argument 'caseSensitive' must be a boolean"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `searching from a file path reports a non-empty file path in output and details`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            dir.resolve("sample.txt").writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", "sample.txt")
                    put("mode", "plain")
                },
                context(dir)
            )

            val success = assertSuccess(result)
            assertTrue(success.output!!.contains("=== file: sample.txt ==="), "output=${success.output}")

            val match = success.details!!["matches"]!!.jsonArray.first().jsonObject
            assertEquals("sample.txt", match["path"]!!.jsonPrimitive.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown parameter is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("unknownParam", "value")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Unknown parameter: 'unknownParam'"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple unknown parameters are accumulated`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("query", "hello")
                    put("path", ".")
                    put("badParam1", "value1")
                    put("badParam2", "value2")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Unknown parameter: 'badParam1'"))
            assertTrue(result.errorDetails!!.contains("Unknown parameter: 'badParam2'"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown parameter accumulated alongside other validation errors`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("badParam", "value")
                    put("caseSensitive", "notabool")
                    put("path", ".")
                },
                context(dir)
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(result.errorDetails!!.contains("Unknown parameter: 'badParam'"))
            assertTrue(result.errorDetails!!.contains("Argument 'caseSensitive' must be a boolean"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid maxResults is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("search-text-test")
        try {
            val resultZero = tool.execute(buildInput("query", maxResults = 0), context(dir))
            assertError(resultZero, BuiltInToolExecutionError.INVALID_INPUT)

            val resultNegative = tool.execute(buildInput("query", maxResults = -1), context(dir))
            assertError(resultNegative, BuiltInToolExecutionError.INVALID_INPUT)

            val resultInvalid = tool.execute(
                buildJsonObject {
                    put("query", "query")
                    put("path", ".")
                    put("maxResults", "abc")
                },
                context(dir)
            )
            assertError(resultInvalid, BuiltInToolExecutionError.INVALID_INPUT)
            assertTrue(resultInvalid.errorDetails!!.contains("Argument 'maxResults' must be an integer"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}