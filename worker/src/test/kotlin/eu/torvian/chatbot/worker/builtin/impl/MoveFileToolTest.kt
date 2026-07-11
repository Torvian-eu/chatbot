package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [MoveFileTool].
 *
 * These tests lock down the current intended semantics: in-workspace move/rename, rejection when
 * the destination already exists, missing-source handling, and workspace containment for both the
 * source and destination. They do not redesign the tool.
 */
class MoveFileToolTest {

    private val tool = MoveFileTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [MoveFileTool.execute].
     *
     * @param source Workspace-relative source path.
     * @param destination Workspace-relative destination path.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(source: String, destination: String): JsonObject = buildJsonObject {
        put("source", source)
        put("destination", destination)
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
    fun `moving a file within the workspace succeeds`() = runTest {
        val dir = createTempDirectory("move-file-test")
        try {
            val source = dir.resolve("a.txt")
            source.writeText("content", Charsets.UTF_8)

            val result = tool.execute(buildInput("a.txt", "b.txt"), context(dir))

            assertSuccess(result)
            assertTrue(!source.exists(), "source should no longer exist")
            assertTrue(dir.resolve("b.txt").exists(), "destination should exist")
            assertEquals("content", dir.resolve("b.txt").readText(Charsets.UTF_8))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `moving to an existing destination returns already exists`() = runTest {
        val dir = createTempDirectory("move-file-test")
        try {
            dir.resolve("a.txt").writeText("content", Charsets.UTF_8)
            dir.resolve("b.txt").writeText("existing", Charsets.UTF_8)

            val result = tool.execute(buildInput("a.txt", "b.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.ALREADY_EXISTS)
            // Neither file should have been touched.
            assertEquals("content", dir.resolve("a.txt").readText(Charsets.UTF_8))
            assertEquals("existing", dir.resolve("b.txt").readText(Charsets.UTF_8))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `moving a non-existent source returns not found`() = runTest {
        val dir = createTempDirectory("move-file-test")
        try {
            val result = tool.execute(buildInput("missing.txt", "target.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal on the source is rejected`() = runTest {
        val dir = createTempDirectory("move-file-test")
        try {
            val result = tool.execute(buildInput("../escaped.txt", "target.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal on the destination is rejected`() = runTest {
        val dir = createTempDirectory("move-file-test")
        try {
            dir.resolve("a.txt").writeText("content", Charsets.UTF_8)

            val result = tool.execute(buildInput("a.txt", "../escaped.txt"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
