package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.validation.truncateLinesAndBytes
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers line-ending, decoding, truncation, and Unicode output compatibility semantics.
 *
 * Each test owns its tool fixture and temporary workspace so the class is safe to execute in
 * parallel with the other run-command test classes.
 */
class RunCommandToolOutputSemanticsTest {
    /** Tool instance kept local to this test class to avoid shared mutable fixtures. */
    private val tool = RunCommandTool()

    /**
     * Verifies the run-command behavior described by the scenario name: line endings and trailing empty lines match existing truncation semantics.
     */
    @Test
    fun `line endings and trailing empty lines match existing truncation semantics`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-lines")
        try {
            val source = "x".repeat(4095) + "\r\nsecond\rthird\n"
            val file = dir.resolve("line-endings.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(
                    "cat",
                    listOf(file.fileName.toString()),
                    timeout = 2,
                    maxLines = 10,
                    maxBytes = 10_000
                ),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(
                truncateLinesAndBytes(source, maxLines = 10, maxBytes = 10_000).text,
                RunCommandTestSupport.stringDetail(result, "stdout"),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: long unterminated line is drained with bounded retention.
     */
    @Test
    fun `long unterminated line is drained with bounded retention`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-long-line")
        try {
            val source = "LONG_LINE_PREFIX" + "x".repeat(256 * 1024) + "LONG_LINE_TAIL"
            val file = dir.resolve("long-line.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(
                    "cat",
                    listOf(file.fileName.toString()),
                    timeout = 2,
                    maxLines = 2,
                    maxBytes = 64
                ),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            val stdout = RunCommandTestSupport.stringDetail(result, "stdout")
            assertTrue(stdout.startsWith("LONG_LINE_PREFIX"))
            assertFalse(stdout.contains("LONG_LINE_TAIL"))
            assertTrue(stdout.toByteArray(Charsets.UTF_8).size <= 64)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: malformed utf8 uses replacement characters.
     */
    @Test
    fun `malformed utf8 uses replacement characters`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-malformed-utf8")
        try {
            val file = dir.resolve("malformed.bin")
            file.toFile().writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x41))
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput("cat", listOf(file.fileName.toString()), timeout = 2, maxBytes = 16),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals("\uFFFD(A", RunCommandTestSupport.stringDetail(result, "stdout"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: supplementary code points remain intact across reader buffer boundaries.
     */
    @Test
    fun `supplementary code points remain intact across reader buffer boundaries`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-supplementary")
        try {
            val source = "a".repeat(4095) + "😀\n"
            val file = dir.resolve("supplementary.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(
                    "cat",
                    listOf(file.fileName.toString()),
                    timeout = 2,
                    maxLines = 2,
                    maxBytes = 4_100
                ),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(source, RunCommandTestSupport.stringDetail(result, "stdout"))
            assertTrue(RunCommandTestSupport.stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 4_100)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
