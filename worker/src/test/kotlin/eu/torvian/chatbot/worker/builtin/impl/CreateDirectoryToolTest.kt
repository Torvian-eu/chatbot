package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [CreateDirectoryTool].
 *
 * These tests lock down the current intended semantics: nested directory creation, idempotent
 * re-creation, and workspace containment. They do not redesign the tool.
 */
class CreateDirectoryToolTest {

    private val tool = CreateDirectoryTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [CreateDirectoryTool.execute].
     *
     * @param path Workspace-relative directory path.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(path: String): JsonObject = buildJsonObject {
        put("path", path)
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
    fun `creating a nested directory structure succeeds`() = runTest {
        val dir = createTempDirectory("create-dir-test")
        try {
            val result = tool.execute(buildInput("a/b/c"), context(dir))

            assertSuccess(result)
            assertTrue(dir.resolve("a/b/c").isDirectory(), "nested directory should exist")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `re-creating an existing directory is idempotent and succeeds silently`() = runTest {
        val dir = createTempDirectory("create-dir-test")
        try {
            val target = dir.resolve("a/b/c")
            target.toFile().mkdirs()

            val result = tool.execute(buildInput("a/b/c"), context(dir))

            assertSuccess(result)
            assertTrue(target.isDirectory(), "directory should still exist after re-creation")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("create-dir-test")
        try {
            val result = tool.execute(buildInput("../escaped"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

