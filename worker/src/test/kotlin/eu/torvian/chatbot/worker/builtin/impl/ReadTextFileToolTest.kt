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
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [ReadTextFileTool].
 *
 * These tests lock down the current intended semantics: full UTF-8 reads, `[start, end)` line
 * range selection with Python slice semantics (0-based, negative-from-end, null open-ended),
 * range validation, missing-file handling, and workspace containment. They do not redesign the tool.
 */
class ReadTextFileToolTest {

    private val tool = ReadTextFileTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [ReadTextFileTool.execute].
     *
     * @param path Workspace-relative file path.
     * @param range Optional `[start, end)` line range; elements may be null for an open end.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(path: String, range: Pair<Int?, Int?>? = null): JsonObject = buildJsonObject {
        put("path", path)
        if (range != null) {
            put("range", buildJsonArray {
                add(range.first?.let { JsonPrimitive(it) } ?: JsonNull)
                add(range.second?.let { JsonPrimitive(it) } ?: JsonNull)
            })
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
    fun `reads a file as UTF-8 returning the full content`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt"), context(dir))

            assertEquals("=== sample.txt (lines:1-3 of 3) ===\nline1\nline2\nline3", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `range selects a half-open start-end slice`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", 1 to 3), context(dir))

            assertEquals("=== sample.txt (lines:2-3 of 4) ===\nline2\nline3", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `null start with end returns the first N lines`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", null to 2), context(dir))

            assertEquals("=== sample.txt (lines:1-2 of 4) ===\nline1\nline2", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `negative start counts from the end`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", -2 to null), context(dir))

            assertEquals("=== sample.txt (lines:3-4 of 4) ===\nline3\nline4", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `negative end counts from the end`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", 1 to -1), context(dir))

            assertEquals("=== sample.txt (lines:2-3 of 4) ===\nline2\nline3", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `out-of-range bounds are clamped rather than rejected`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", 1 to 100), context(dir))

            assertEquals("=== sample.txt (lines:2-3 of 3) ===\nline2\nline3", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `range with both bounds null is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", null to null), context(dir))

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `range with wrong arity is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(
                buildJsonObject { put("path", "sample.txt"); put("range", buildJsonArray { add(0) }) },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `requesting a non-existent file returns not found`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val result = tool.execute(buildInput("missing.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val result = tool.execute(buildInput("../escaped.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
