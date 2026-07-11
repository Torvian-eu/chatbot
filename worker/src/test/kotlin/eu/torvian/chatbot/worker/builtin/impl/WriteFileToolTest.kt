package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [WriteFileTool].
 *
 * These tests lock down the current intended semantics: automatic parent-directory creation,
 * clean overwrite of existing files, UTF-8 content persistence, and workspace containment. They
 * do not redesign the tool.
 */
class WriteFileToolTest {

    private val tool = WriteFileTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [WriteFileTool.execute].
     *
     * @param path Workspace-relative file path.
     * @param content Text content to write.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(path: String, content: String): JsonObject = buildJsonObject {
        put("path", path)
        put("content", content)
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
     * Reads the full text content of [file].
     */
    private fun readFile(file: Path): String = file.readText(Charsets.UTF_8)

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
    fun `writing a new file creates parent directories and saves UTF-8 content`() = runTest {
        val dir = createTempDirectory("write-file-test")
        try {
            val result = tool.execute(buildInput("nested/deep/file.txt", "hello world"), context(dir))

            assertSuccess(result)
            val file = dir.resolve("nested/deep/file.txt")
            assertTrue(file.toFile().exists(), "parent directories should have been created")
            assertEquals("hello world", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writing to an existing path overwrites it cleanly`() = runTest {
        val dir = createTempDirectory("write-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.toFile().writeText("original content", Charsets.UTF_8)

            val result = tool.execute(buildInput("sample.txt", "replaced content"), context(dir))

            assertSuccess(result)
            assertEquals("replaced content", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("write-file-test")
        try {
            val result = tool.execute(buildInput("../escaped.txt", "data"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

