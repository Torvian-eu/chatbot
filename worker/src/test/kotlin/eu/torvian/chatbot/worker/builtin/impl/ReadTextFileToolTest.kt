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
 * These tests lock down the current intended semantics: full UTF-8 reads, `head`/`tail` line
 * selection, mutual exclusion of `head` and `tail`, missing-file handling, and workspace
 * containment. They do not redesign the tool.
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
     * @param head When non-null, number of leading lines to return.
     * @param tail When non-null, number of trailing lines to return.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(path: String, head: Int? = null, tail: Int? = null): JsonObject = buildJsonObject {
        put("path", path)
        if (head != null) put("head", head)
        if (tail != null) put("tail", tail)
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

            assertEquals("line1\nline2\nline3", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `head returns only the first N lines`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", head = 2), context(dir))

            assertEquals("line1\nline2", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tail returns only the last N lines`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3\nline4", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", tail = 2), context(dir))

            assertEquals("line3\nline4", assertSuccess(result))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `supplying both head and tail is rejected as invalid input`() = runTest {
        val dir = createTempDirectory("read-text-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", head = 1, tail = 1), context(dir))

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

