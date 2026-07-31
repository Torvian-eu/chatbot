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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [EditFileTool].
 *
 * These tests lock down the current intended semantics of the tool: all-occurrence replacement,
 * exact matching, deterministic overlap resolution, dry-run vs. write behavior,
 * input validation, and workspace containment. They do not redesign the tool.
 */
class EditFileToolTest {

    private val tool = EditFileTool()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [EditFileTool.execute].
     *
     * @param path Workspace-relative file path.
     * @param edits Ordered list of `(oldText, newText)` edit specs.
     * @param dryRun When non-null, controls the `dryRun` flag; when null the flag is omitted.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        path: String,
        edits: List<Pair<String, String>>,
        dryRun: Boolean? = null,
    ): JsonObject = buildJsonObject {
        put("path", path)
        putJsonArray("edits") {
            for ((oldText, newText) in edits) {
                addJsonObject {
                    put("oldText", oldText)
                    put("newText", newText)
                }
            }
        }
        if (dryRun != null) put("dryRun", dryRun)
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

    /**
     * Every non-overlapping occurrence of a single edit spec is replaced.
     */
    @Test
    fun `replaces all occurrences of a single edit spec`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("foo bar foo bar foo")

            val result = tool.execute(
                buildInput("sample.txt", listOf("foo" to "baz")),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("baz bar baz bar baz", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * An oldText whose whitespace does not exactly match the source fails
     * because matching is exact — every character must be identical.
     */
    @Test
    fun `whitespace mismatch fails with exact matching`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // Single spaces between words in the source.
            file.writeText("cat walks", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("cat  walks" to "dog runs")),
                context(dir),
            )

            // "cat  walks" (two spaces) does not match "cat walks" (one space) exactly.
            assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * An edit spanning multiple lines is applied across newline boundaries.
     */
    @Test
    fun `multiline replacement works across line breaks`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("line1\nline2\nline3", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("line2\nline3" to "replaced")),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("line1\nreplaced", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * With dryRun=true the tool returns the summary + diff but does not write the file.
     */
    @Test
    fun `dryRun returns a report and does not modify the file`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            val original = "foo bar foo"
            file.writeText(original, Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("foo" to "baz"), dryRun = true),
                context(dir),
            )

            val output = assertSuccess(result)
            // File must remain untouched.
            assertEquals(original, readFile(file))
            // Report must summarize the planned changes.
            assertTrue(output.contains("requested edit specs: 1"), "report missing requested count")
            assertTrue(output.contains("matched occurrences: 2"), "report missing matched count")
            assertTrue(output.contains("applied occurrences: 2"), "report missing applied count")
            assertTrue(output.contains("rejected occurrences: 0"), "report missing rejected count")
            assertTrue(output.contains("--- diff ---"), "report missing diff section")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * With dryRun=false the modified content is actually written back to the file.
     */
    @Test
    fun `non-dryRun writes the modified file`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("foo bar foo", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("foo" to "baz"), dryRun = false),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("baz bar baz", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * An oldText that matches zero occurrences fails with EXECUTION_FAILED ("not found").
     */
    @Test
    fun `edit spec with zero matches fails with an error result`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("missing" to "x")),
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
            assertEquals(
                true,
                result.errorMessage?.contains("not found"),
                "error message should mention not found: ${result.errorMessage}"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
    /**
     * A whitespace-only oldText is rejected as INVALID_INPUT before any matching.
     */
    @Test
    fun `blank oldText is rejected with INVALID_INPUT`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("   " to "x")),
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A non-string oldText (e.g. a number) is rejected with a precise error message.
     */
    @Test
    fun `non-string oldText is rejected with INVALID_INPUT`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildJsonObject {
                    put("path", "sample.txt")
                    putJsonArray("edits") {
                        addJsonObject {
                            put("oldText", JsonPrimitive(42))
                            put("newText", JsonPrimitive("x"))
                        }
                    }
                },
                context(dir),
            )
            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertEquals(
                true,
                result.errorDetails?.contains("must be a string"),
                "error details should say 'must be a string': ${result.errorDetails}"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A non-string newText (e.g. a number) is rejected with a precise error message.
     */
    @Test
    fun `non-string newText is rejected with INVALID_INPUT`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildJsonObject {
                    put("path", "sample.txt")
                    putJsonArray("edits") {
                        addJsonObject {
                            put("oldText", JsonPrimitive("x"))
                            put("newText", JsonPrimitive(42))
                        }
                    }
                },
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            assertEquals(
                true,
                result.errorDetails?.contains("must be a string"),
                "error details should say 'must be a string': ${result.errorDetails}"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Several independent edit specs each apply to their own non-overlapping region.
     */
    @Test
    fun `non-overlapping multiple edit specs all apply`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("alpha beta gamma", Charsets.UTF_8)

            val result = tool.execute(
                buildInput(
                    "sample.txt",
                    listOf("alpha" to "A", "beta" to "B", "gamma" to "C"),
                ),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("A B C", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Multiple non-overlapping "aa" runs inside one token ("aaaaaa") each map to
     * their own original-space range and are replaced.
     */
    @Test
    fun `repeated intra-token matches are replaced correctly`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // Six characters with no whitespace: three non-overlapping "aa" runs.
            file.writeText("aaaaaa", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("aa" to "X")),
                context(dir),
            )

            assertSuccess(result)
            // Each "aa" run must map to its own original-space range and be replaced.
            assertEquals("XXX", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * When spans overlap, the longer (more specific) match wins; shorter rejected.
     */
    @Test
    fun `overlapping occurrences prefer the larger or more specific match`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // "aaaa" contains two non-overlapping "aa" occurrences and one "aaaa" occurrence.
            file.writeText("aaaa", Charsets.UTF_8)

            val result = tool.execute(
                buildInput(
                    "sample.txt",
                    listOf(
                        "aa" to "X",   // index 0, span 2
                        "aaaa" to "Y", // index 1, span 4 (more specific -> wins)
                    ),
                ),
                context(dir),
            )

            val output = assertSuccess(result)
            // The whole string is replaced by the longer match.
            assertEquals("Y", readFile(file))
            // Report: 2 edit specs, 3 matched (two "aa" + one "aaaa"), 1 applied, 2 rejected.
            assertTrue(output.contains("requested edit specs: 2"), "report missing requested count")
            assertTrue(output.contains("matched occurrences: 3"), "report missing matched count")
            assertTrue(output.contains("applied occurrences: 1"), "report missing applied count")
            assertTrue(output.contains("rejected occurrences: 2"), "report missing rejected count")
            // Rejected occurrences must identify the edit spec index and original-space range.
            assertTrue(output.contains("edit spec index 0"), "report missing rejected edit spec index")
            assertTrue(output.contains("original range ["), "report missing rejected original range")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Equal-length overlapping spans are broken by the lower edit-spec index.
     */
    @Test
    fun `equal-priority overlap uses lower edit spec index as tie-breaker`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("abc", Charsets.UTF_8)

            val result = tool.execute(
                buildInput(
                    "sample.txt",
                    listOf(
                        "abc" to "X", // index 0 (kept on tie)
                        "abc" to "Y", // index 1 (rejected on tie)
                    ),
                ),
                context(dir),
            )

            val output = assertSuccess(result)
            // Lower edit spec index wins the tie.
            assertEquals("X", readFile(file))
            assertTrue(output.contains("requested edit specs: 2"), "report missing requested count")
            assertTrue(output.contains("matched occurrences: 2"), "report missing matched count")
            assertTrue(output.contains("applied occurrences: 1"), "report missing applied count")
            assertTrue(output.contains("rejected occurrences: 1"), "report missing rejected count")
            assertTrue(output.contains("edit spec index 1"), "report missing rejected edit spec index")
            assertTrue(output.contains("original range ["), "report missing rejected original range")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A match that ends at end-of-file is applied correctly.
     */
    @Test
    fun `match at end of file works`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("world" to "there")),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("hello there", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A tab in the source must be matched exactly with the same tab in oldText.
     */
    @Test
    fun `exact tab matching replaces correctly`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // Tab between words and a trailing newline in the source.
            file.writeText("cat\twalks\n", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("cat\twalks\n" to "dog\truns\n")),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("dog\truns\n", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A path escaping the workspace (../escape.txt) is rejected with
     * WORKSPACE_VIOLATION.
     */
    @Test
    fun `workspace escape path is rejected`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val result = tool.execute(
                buildInput("../escape.txt", listOf("a" to "b")),
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.WORKSPACE_VIOLATION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A whitespace-leading multiline edit keeps the first line's indentation at exactly
     * one level: the original indentation is inside the matched range and replaced by
     * newText, so no doubling occurs.
     */
    @Test
    fun `indentation of the first matched line is not doubled`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.kt")
            // The matched block carries 4 spaces of indentation on its first line.
            val original = "class A {\n    fun foo() {\n        bar()\n    }\n}\n"
            file.writeText(original, Charsets.UTF_8)

            // oldText / newText supply their own (single) 4-space indentation on the first line.
            val oldText = "    fun foo() {\n        bar()\n    }"
            val newText = "    fun foo() {\n        baz()\n    }"

            val result = tool.execute(
                buildInput("sample.kt", listOf(oldText to newText)),
                context(dir),
            )

            assertSuccess(result)
            val expected = "class A {\n    fun foo() {\n        baz()\n    }\n}\n"
            assertEquals(expected, readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A nested in-workspace path is accepted and the file is edited in place.
     */
    @Test
    fun `normal in-workspace path is accepted`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val sub = dir.resolve("sub")
            sub.toFile().mkdirs()
            val file = sub.resolve("nested.txt")
            file.writeText("value", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sub/nested.txt", listOf("value" to "changed")),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("changed", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A tab in the source is not equivalent to a space; exact matching rejects
     * mismatched whitespace.
     */
    @Test
    fun `tab does not match space in exact mode`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // Tab between words in the source.
            file.writeText("cat\twalks", Charsets.UTF_8)

            // oldText uses a space instead of a tab — exact match fails.
            val result = tool.execute(
                buildInput("sample.txt", listOf("cat walks" to "dog runs")),
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Exact interior whitespace matching: the oldText must match the source
     * character-for-character, so a source with three inter-word spaces requires
     * exactly those three spaces in oldText.
     */
    @Test
    fun `exact interior whitespace matching works`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // Three inter-word spaces in the source.
            file.writeText("alpha   beta   gamma", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("alpha   beta   gamma" to "A   B   C")),
                context(dir),
            )

            assertSuccess(result)
            // All three spaces matched exactly and are replaced.
            assertEquals("A   B   C", readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Explicit CRLF (windows) line endings are preserved and the first-line
     * indentation is not doubled.
     */
    @Test
    fun `first-line indentation is preserved once on CRLF (windows) files`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.kt")
            // Explicit CRLF line endings (writeText keeps \r\n as-is; Files.readString preserves it).
            val original = "class A {\r\n    fun foo() {\r\n        bar()\r\n    }\r\n}\r\n"
            file.writeText(original, Charsets.UTF_8)

            val oldText = "    fun foo() {\r\n        bar()\r\n    }"
            val newText = "    fun foo() {\r\n        baz()\r\n    }"

            val result = tool.execute(
                buildInput("sample.kt", listOf(oldText to newText)),
                context(dir),
            )

            assertSuccess(result)
            // The match range starts at the indentation after the \n of each \r\n pair, so the
            // leading \r\n is preserved and the indentation is replaced wholesale by newText:
            // single indentation, and the file stays CRLF (no stray bare \r, no doubling).
            val expected = "class A {\r\n    fun foo() {\r\n        baz()\r\n    }\r\n}\r\n"
            assertEquals(expected, readFile(file))
            assertFalse(readFile(file).contains("        fun"), "first line must not be double-indented")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Unified diff output (Git-compatible) — regression guards for inaccurate diff reporting
    // -----------------------------------------------------------------------------------------

    /**
     * An early single-line edit must produce a compact diff: the unchanged trailing lines
     * (which merely shift down by one index) must appear as context, never as changed lines.
     * This guards the "small change at the beginning → very long diff" symptom.
     */
    @Test
    fun `early edit produces compact unified diff with unchanged lines as context`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            val original = "alpha\nbeta\ngamma\ndelta\nepsilon"
            file.writeText(original, Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("alpha" to "ALPHA"), dryRun = true),
                context(dir),
            )

            val output = assertSuccess(result)
            assertTrue(output.contains("--- diff ---"), "report missing diff section")
            // The diff must contain a single hunk header.
            val hunkHeaders = output.lines().filter { it.startsWith("@@") }
            assertEquals(1, hunkHeaders.size, "expected exactly one hunk, got:\n$output")
            // Only "alpha" changed; it must be the sole - / + pair.
            assertTrue(output.contains("- alpha"), "expected removed line alpha")
            assertTrue(output.contains("+ ALPHA"), "expected added line ALPHA")
            // All other lines must be context (space prefix) or absent, never - / +.
            for (line in listOf("beta", "gamma", "delta", "epsilon")) {
                assertTrue(
                    !output.contains("- $line") && !output.contains("+ $line"),
                    "unchanged line '$line' must not be reported as changed in:\n$output"
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Inserting a line near the top is shown as a single added line; the shifted-down existing
     * lines are context only, not deleted/re-added.
     */
    @Test
    fun `insertion near top is shown as a single added line in diff`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            val original = "one\ntwo\nthree"
            file.writeText(original, Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("one" to "one\nONE"), dryRun = true),
                context(dir),
            )

            val output = assertSuccess(result)
            assertTrue(output.contains("+ ONE"), "expected inserted line ONE in diff")
            // two/three are unchanged and merely shifted; they must not be flagged as changed.
            for (line in listOf("two", "three")) {
                assertTrue(
                    !output.contains("- $line") && !output.contains("+ $line"),
                    "shifted line '$line' must not be reported as changed in:\n$output"
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * When the applied changes produce no textual difference, the report says "(no changes)"
     * rather than dumping the entire file as a positional diff.
     */
    @Test
    fun `no-op edit reports no changes rather than a full dump`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.txt")
            // oldText equals newText -> applied content equals original.
            file.writeText("hello world", Charsets.UTF_8)

            val result = tool.execute(
                buildInput("sample.txt", listOf("hello" to "hello"), dryRun = true),
                context(dir),
            )

            val output = assertSuccess(result)
            assertTrue(output.contains("(no changes)"), "expected (no changes) marker, got:\n$output")
            assertFalse(output.contains("@@"), "no-op diff must not contain hunk headers")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Trailing whitespace in oldText must not consume the next line's indentation
    // -----------------------------------------------------------------------------------------

    /**
     * When oldText ends with a newline, the next line's indentation must not
     * be consumed into the match range and erased. With exact matching, the
     * match ends exactly at `hi + needle.length`, so trailing whitespace cannot
     * overshoot into the next line.
     */
    @Test
    fun `trailing newline in oldText does not eat next line indentation`() = runTest {
        val dir = createTempDirectory("edit-file-test")
        try {
            val file = dir.resolve("sample.kt")
            val original = "    val x = 1\n    val y = 2\n"
            file.writeText(original, Charsets.UTF_8)

            // oldText ends with a newline; the next line's indentation (4 spaces)
            // must survive the replacement.
            val oldText = "    val x = 1\n"
            val newText = "    val x = 42\n"

            val result = tool.execute(
                buildInput("sample.kt", listOf(oldText to newText)),
                context(dir),
            )

            assertSuccess(result)
            val expected = "    val x = 42\n    val y = 2\n"
            assertEquals(expected, readFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
