package eu.torvian.chatbot.worker.builtin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [LineDiff.unifiedDiff].
 *
 * These lock down the Git-compatible unified-diff behavior: LCS-based alignment (so unchanged
 * lines that merely shift are not reported as changed), `@@` hunk headers with correct
 * 1-based line numbers and counts, surrounding context lines, and the no-change fast path.
 */
class LineDiffTest {

    /**
     * Two identical inputs yield no diff body.
     */
    @Test
    fun `identical inputs produce empty diff`() {
        val lines = listOf("a", "b", "c")
        assertEquals("", LineDiff.unifiedDiff(lines, lines))
    }

    /**
     * A single-line change in the middle is reported as one hunk with context and correct
     * `@@ -s,n +t,m @@` numbers (1-based).
     */
    @Test
    fun `single middle line change produces one hunk with context`() {
        val original = listOf("one", "two", "three", "four", "five")
        val modified = listOf("one", "TWO", "three", "four", "five")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        // Only "two" -> "TWO" changed, so the hunk is: context "one", - "two", + "TWO",
        // context "three", "four", "five".
        val expected = """
            @@ -1,5 +1,5 @@
              one
            - two
            + TWO
              three
              four
              five
        """.trimIndent() + "\n"

        assertEquals(expected, diff)
    }

    /**
     * A change near the top must NOT cause every later (unchanged, merely shifted) line to be
     * reported. This is the regression guard for the "small early change → huge diff" bug.
     */
    @Test
    fun `early single-line edit does not report unchanged trailing lines`() {
        val original = listOf("A", "B", "C", "D", "E")
        val modified = listOf("A'", "B", "C", "D", "E")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        // The hunk must contain exactly the changed line and its context; B/C/D/E must appear
        // as context (space prefix) or not at all, never as - / + pairs.
        assertTrue(diff.contains("+ A'"), "expected added line A'")
        assertTrue(diff.contains("- A"), "expected removed line A")
        // Unchanged lines must be context only.
        for (line in listOf("B", "C", "D", "E")) {
            assertTrue(diff.contains("  $line"), "expected '$line' as context, got:\n$diff")
            assertTrue(!diff.contains("- $line") && !diff.contains("+ $line"),
                "unchanged line '$line' must not be reported as changed:\n$diff")
        }
    }

    /**
     * Inserting a line near the top reports only the inserted line; existing lines that merely
     * shift down must not be flagged as changed or removed.
     */
    @Test
    fun `insertion near top is reported without shifting false changes`() {
        val original = listOf("A", "B", "C", "D")
        val modified = listOf("A", "X", "B", "C", "D")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        assertTrue(diff.contains("+ X"), "expected inserted line X")
        // B/C/D are unchanged and merely shifted; they must be context, not - / +.
        for (line in listOf("B", "C", "D")) {
            assertTrue(!diff.contains("- $line") && !diff.contains("+ $line"),
                "shifted line '$line' must not be reported as changed:\n$diff")
        }
    }

    /**
     * Deleting a line near the top reports only the removed line; later lines must not be flagged.
     */
    @Test
    fun `deletion near top is reported without shifting false changes`() {
        val original = listOf("A", "B", "C", "D")
        val modified = listOf("A", "C", "D")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        assertTrue(diff.contains("- B"), "expected removed line B")
        for (line in listOf("C", "D")) {
            assertTrue(!diff.contains("- $line") && !diff.contains("+ $line"),
                "shifted line '$line' must not be reported as changed:\n$diff")
        }
    }

    /**
     * Replacing N lines with M lines yields correct hunk spans.
     */
    @Test
    fun `multi-line replacement has correct hunk counts`() {
        val original = listOf("keep1", "old1", "old2", "keep2")
        val modified = listOf("keep1", "new1", "new2", "new3", "keep2")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 1)

        // Keep1 (context), then old1/old2 removed, new1/new2/new3 added, keep2 (context).
        // Original span: 4 lines (1..4), modified span: 5 lines (1..5).
        assertEquals("@@ -1,4 +1,5 @@", diff.lines().first())
        assertTrue(diff.contains("- old1"))
        assertTrue(diff.contains("- old2"))
        assertTrue(diff.contains("+ new1"))
        assertTrue(diff.contains("+ new2"))
        assertTrue(diff.contains("+ new3"))
    }

    /**
     * Two distant changes are split into two hunks when the gap exceeds 2*contextLines, and
     * the surplus middle context is dropped (each hunk shows only its own context).
     */
    @Test
    fun `distant changes are split into separate hunks`() {
        val original = (1..20).map { "line$it" }.toMutableList()
        val modified = original.toMutableList().also {
            it[1] = "line1-CHANGED"
            it[18] = "line18-CHANGED"
        }

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        val hunkHeaders = diff.lines().filter { it.startsWith("@@") }
        assertEquals(2, hunkHeaders.size, "expected two hunks, got:\n$diff")
        assertTrue(diff.contains("+ line1-CHANGED"))
        assertTrue(diff.contains("+ line18-CHANGED"))
    }

        /**
     * A trailing insertion keeps the unchanged preceding lines as context and reports the
     * added line with a correct hunk header (`oldCount` counts the context lines, not `0`).
     */
    @Test
    fun `trailing insertion hunk keeps preceding context in its counts`() {
        val original = listOf("a", "b", "c")
        val modified = listOf("a", "b", "c", "d")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        // Existing a/b/c are context; only d is added => @@ -1,3 +1,4 @@.
        assertEquals("@@ -1,3 +1,4 @@", diff.lines().first())
        assertTrue(diff.contains("+ d"), "expected added line d")
    }

    /**
     * A trailing deletion keeps the unchanged preceding lines as context and reports the
     * removed line with a correct hunk header (`newCount` counts the context lines, not `0`).
     */
    @Test
    fun `trailing deletion hunk keeps preceding context in its counts`() {
        val original = listOf("a", "b", "c")
        val modified = listOf("a", "b")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        // Existing a/b are context; only c is removed => @@ -1,3 +1,2 @@.
        assertEquals("@@ -1,3 +1,2 @@", diff.lines().first())
        assertTrue(diff.contains("- c"), "expected removed line c")
    }

        /**
     * Inserting into an originally empty file yields a pure-insertion hunk whose original span
     * count is `0` (`oldCount == 0`), matching Git's convention. The anchor line is reported as
     * `1` (there is no preceding line to point at).
     */
    @Test
    fun `insertion into empty file yields zero old count`() {
        val original = emptyList<String>()
        val modified = listOf("d")

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        assertEquals("@@ -1,0 +1,1 @@", diff.lines().first())
        assertTrue(diff.contains("+ d"))
    }

    /**
     * A missing final newline is preserved: splitting on '\n' yields a trailing empty string,
     * so it is not falsely invented.
     */
    @Test
    fun `missing final newline is preserved not invented`() {
        // "a\nb" splits to ["a","b"]; "a\nb\n" splits to ["a","b",""].
        val original = listOf("a", "b")          // no trailing newline
        val modified = listOf("a", "b", "")       // trailing newline

        val diff = LineDiff.unifiedDiff(original, modified, contextLines = 3)

        // The only difference is the trailing newline, which should be shown as a context/+ pair
        // or simply an added empty line; crucially it must not be dropped silently (diff non-empty).
        assertTrue(diff.isNotEmpty(), "missing-vs-present final newline must be reported")
    }

    /**
     * Negative context lines are rejected.
     */
    @Test
    fun `negative context lines are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LineDiff.unifiedDiff(listOf("a"), listOf("b"), contextLines = -1)
        }
    }
}
