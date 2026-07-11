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
 * Scenario-driven unit tests for [ListDirectoryTool].
 *
 * These tests lock down the current intended semantics: `[FILE]`/`[DIR]` prefixes, name/size
 * sorting, optional size suffixes, recursive indentation, missing-directory handling, and
 * workspace containment. They do not redesign the tool.
 */
class ListDirectoryToolTest {

    private val tool = ListDirectoryTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [ListDirectoryTool.execute].
     *
     * @param path Workspace-relative directory path (defaults to "." when null).
     * @param sortBy Sort key: "name" or "size".
     * @param includeSizes When true, append byte sizes to files.
     * @param recursive When true, list subdirectories recursively.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        path: String? = null,
        sortBy: String? = null,
        includeSizes: Boolean? = null,
        recursive: Boolean? = null,
    ): JsonObject = buildJsonObject {
        if (path != null) put("path", path)
        if (sortBy != null) put("sortBy", sortBy)
        if (includeSizes != null) put("includeSizes", includeSizes)
        if (recursive != null) put("recursive", recursive)
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
    fun `flat listing uses FILE and DIR prefixes`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            dir.resolve("notes.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()

            val result = tool.execute(buildInput("."), context(dir))

            val output = assertSuccess(result)
            val lines = output.lines()
            assertTrue(lines.any { it.startsWith("[FILE] notes.txt") }, "expected file entry; got:\n$output")
            assertTrue(lines.any { it.startsWith("[DIR] sub") }, "expected dir entry; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sorting by name orders alphabetically case-insensitive`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            dir.resolve("banana.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("apple.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("Cherry.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("Zebra").toFile().mkdirs()

            val result = tool.execute(buildInput(".", sortBy = "name"), context(dir))

            val names = assertSuccess(result).lines().map { it.removePrefix("[FILE] ").removePrefix("[DIR] ") }
            assertEquals(listOf("apple.txt", "banana.txt", "Cherry.txt", "Zebra"), names)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sorting by size orders by file sizes with directories lowest`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            dir.resolve("small.txt").writeText("a", Charsets.UTF_8)        // 1 byte
            dir.resolve("large.txt").writeText("aaaaaa", Charsets.UTF_8)   // 6 bytes
            dir.resolve("docs").toFile().mkdirs()                          // directory -> lowest

            val result = tool.execute(buildInput(".", sortBy = "size"), context(dir))

            val names = assertSuccess(result).lines().map { it.removePrefix("[FILE] ").removePrefix("[DIR] ") }
            // Directories sort lowest (-1L), then files by ascending size.
            assertEquals(listOf("docs", "small.txt", "large.txt"), names)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `includeSizes appends byte count to files`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            dir.resolve("data.txt").writeText("abc", Charsets.UTF_8) // 3 bytes

            val result = tool.execute(buildInput(".", includeSizes = true), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("[FILE] data.txt  (3 bytes)"), "expected size suffix; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recursive listing indents subdirectory contents by two spaces per level`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            dir.resolve("top.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub").toFile().mkdirs()
            dir.resolve("sub/inner.txt").writeText("x", Charsets.UTF_8)
            dir.resolve("sub/deep").toFile().mkdirs()
            dir.resolve("sub/deep/leaf.txt").writeText("x", Charsets.UTF_8)

            val result = tool.execute(buildInput(".", recursive = true), context(dir))

            val output = assertSuccess(result)
            val lines = output.lines()
            // Top-level entries have no indentation; nested entries are indented by 2 spaces/level.
            assertTrue(lines.any { it == "[FILE] top.txt" }, "expected top-level file; got:\n$output")
            assertTrue(lines.any { it == "[DIR] sub" }, "expected top-level sub dir; got:\n$output")
            assertTrue(lines.any { it == "  [FILE] inner.txt" }, "expected 2-space indented inner file; got:\n$output")
            assertTrue(lines.any { it == "  [DIR] deep" }, "expected 2-space indented deep dir; got:\n$output")
            assertTrue(lines.any { it == "    [FILE] leaf.txt" }, "expected 4-space indented leaf file; got:\n$output")
            // The tool walks depth-first: the deep subtree is listed immediately after "sub".
            val subIdx = lines.indexOfFirst { it == "[DIR] sub" }
            val deepIdx = lines.indexOfFirst { it == "  [DIR] deep" }
            val innerIdx = lines.indexOfFirst { it == "  [FILE] inner.txt" }
            assertTrue(deepIdx > subIdx, "deep dir should appear after sub dir")
            assertTrue(innerIdx > deepIdx, "inner file should appear after the deep subtree")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `requesting a non-existent directory returns not found`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            val result = tool.execute(buildInput("missing"), context(dir))

            assertError(result, BuiltInToolExecutionError.NOT_FOUND)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `path traversal escaping the workspace is rejected`() = runTest {
        val dir = createTempDirectory("list-dir-test")
        try {
            val result = tool.execute(buildInput("../escaped"), context(dir))

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
