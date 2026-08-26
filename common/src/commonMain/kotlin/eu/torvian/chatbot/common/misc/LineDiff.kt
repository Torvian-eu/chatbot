package eu.torvian.chatbot.common.misc

/**
 * Line-based unified-diff utilities, matching the layout Git uses (hunk headers,
 * context lines, and `+ ` / `- ` / ` ` line prefixes).
 *
 * The implementation is intentionally self-contained (no third-party diff library): it
 * computes a longest-common-subsequence edit script between two line sequences and groups the
 * changes into hunks with surrounding context. This keeps the diff compact and correct even
 * when an edit near the top of a file shifts the alignment of every later line — unlike a
 * naive positional line-by-line comparison, which would falsely flag all shifted lines as
 * changed.
 */
object LineDiff {

    /**
     * A single operation in the edit script derived from the longest common subsequence.
     *
     * Can be [EQUAL] (line is unchanged), [DELETE] (line was removed), or [INSERT] (line was added).
     */
    private enum class Op {
        /** Both inputs share this line at the corresponding position. */
        EQUAL,
        /** The line exists only in the original (removed in the modified version). */
        DELETE,
        /** The line exists only in the modified version (added relative to the original). */
        INSERT
    }

    /**
     * Computes a Git-style unified diff between two line sequences.
     *
     * The algorithm first builds a longest-common-subsequence table over the two line lists,
     * backtracks it to an edit script of [Op]s, and then groups consecutive operations into
     * hunks. Each hunk is prefixed by a `@@ -oldStart,oldCount +newStart,newCount @@` header
     * using 1-based, inclusive line numbers (matching Git's convention, including its special
     * casing for pure insertions/deletions where the count is `0`), and is surrounded by up to
     * [contextLines] unchanged lines on each side. Context lines are prefixed with a single
     * space, removed lines with `- `, and added lines with `+ `.
     *
     * @param originalLines Lines of the original content (each entry is the line text *without*
     *   its terminating newline; a trailing empty string represents a missing final newline).
     * @param modifiedLines Lines of the modified content, in the same representation as [originalLines].
     * @param contextLines Number of unchanged context lines to show around each change (defaults to 3,
     *   matching Git). Must be non-negative.
     * @return The unified-diff body with hunk headers and prefixed lines, or an empty string when
     *   the two inputs are identical (no diff to show).
     */
    fun unifiedDiff(
        originalLines: List<String>,
        modifiedLines: List<String>,
        contextLines: Int = 3,
    ): String {
        require(contextLines >= 0) { "contextLines must be non-negative, was $contextLines" }
        if (originalLines == modifiedLines) return ""

        val script = computeEditScript(originalLines, modifiedLines)
        val hunks = groupIntoHunks(script, contextLines)

        val sb = StringBuilder()
        for (hunk in hunks) {
            appendHunkHeader(sb, hunk)
            for ((op, text) in hunk.ops) {
                when (op) {
                    Op.EQUAL -> sb.append("  ").append(text).append('\n')
                    Op.DELETE -> sb.append("- ").append(text).append('\n')
                    Op.INSERT -> sb.append("+ ").append(text).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * One entry of the aligned edit script: an operation plus the line text it refers to
     * (the original line for [Op.EQUAL]/[Op.DELETE], the modified line for [Op.INSERT]).
     *
     * @property op The kind of change.
     * @property text The line content associated with [op].
     */
    private data class ScriptEntry(val op: Op, val text: String)

    /**
     * A contiguous slice of the edit script plus the original/modified line ranges it spans,
     * used to render one `@@` hunk.
     *
     * @property ops Aligned operations (including leading/trailing context) that make up the hunk.
     * @property origStart 1-based original line index where the hunk's first op sits.
     * @property newStart 1-based modified line index where the hunk's first op sits.
     */
    private data class Hunk(val ops: List<ScriptEntry>, val origStart: Int, val newStart: Int)

    /**
     * Builds the longest-common-subsequence dynamic-programming table and backtracks it into a
     * full edit script.
     *
     * The DP table `dp[i][j]` holds the LCS length between the suffixes
     * `originalLines[i..n)` and `modifiedLines[j..m)`. Backtracking from the cell `(0, 0)` yields
     * the minimal number of insert/delete operations (equal lines are shared). Time and space are
     * `O(originalLines.size * modifiedLines.size)`, which is acceptable for the source-file
     * sizes this tool edits.
     *
     * @param originalLines Source line list.
     * @param modifiedLines Target line list.
     * @return The edit script with [Op.EQUAL], [Op.DELETE], and [Op.INSERT] entries in order.
     */
    private fun computeEditScript(originalLines: List<String>, modifiedLines: List<String>): List<ScriptEntry> {
        val n = originalLines.size
        val m = modifiedLines.size

                // dp[i][j] = LCS length of originalLines[i..n) and modifiedLines[j..m).
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (originalLines[i] == modifiedLines[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val script = mutableListOf<ScriptEntry>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                originalLines[i] == modifiedLines[j] -> {
                    script.add(ScriptEntry(Op.EQUAL, originalLines[i]))
                    i++
                    j++
                }
                // Prefer the branch with the larger remaining LCS to keep the script minimal.
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    script.add(ScriptEntry(Op.DELETE, originalLines[i]))
                    i++
                }
                else -> {
                    script.add(ScriptEntry(Op.INSERT, modifiedLines[j]))
                    j++
                }
            }
        }
        // Drain any remaining lines (only one of the two loops can still execute).
        while (i < n) {
            script.add(ScriptEntry(Op.DELETE, originalLines[i]))
            i++
        }
        while (j < m) {
            script.add(ScriptEntry(Op.INSERT, modifiedLines[j]))
            j++
        }
        return script
    }

    /**
     * Groups a flat edit script into hunks, each padded with up to [contextLines] unchanged
     * lines on either side.
     *
     * The script is split into change *blocks* at every run of [Op.EQUAL] lines longer than
     * `2 * contextLines`; the surplus middle context is dropped, mirroring Git's behavior of
     * showing only a few context lines between distant changes. Each block is then expanded with
     * up to [contextLines] equal lines of leading/trailing context to form a [Hunk] whose
     * `origStart`/`newStart` are the 1-based line numbers of its first operation.
     *
     * @param script The aligned edit script from [computeEditScript].
     * @param contextLines Number of context lines per side.
     * @return The ordered list of [Hunk]s to render.
     */
    private fun groupIntoHunks(script: List<ScriptEntry>, contextLines: Int): List<Hunk> {
        val hunks = mutableListOf<Hunk>()

        // Identify the inclusive index ranges [start, end] of each change block (runs of
        // non-EQUAL ops), split whenever an EQUAL run exceeds 2*contextLines.
        val blocks = mutableListOf<IntRange>()
        var i = 0
        while (i < script.size) {
            if (script[i].op == Op.EQUAL) {
                i++
                continue
            }
            val blockStart = i
            // Extend the block across adjacent non-EQUAL ops, but break the block (and skip
            // context) when an equal run is too long to keep as intra-block context.
            while (i < script.size) {
                if (script[i].op != Op.EQUAL) {
                    i++
                    continue
                }
                // Count the equal run length starting at i.
                var e = i
                while (e < script.size && script[e].op == Op.EQUAL) e++
                if (e - i > 2 * contextLines) {
                    // This equal run is too long to bridge; end the current block at i and
                    // resume scanning after the run.
                    break
                }
                i = e // equal run fits as context; continue the same block
            }
            blocks.add(blockStart until i)
        }

        // Convert each block into a Hunk, adding up to contextLines equal lines of context
        // on each side, and computing the 1-based start line numbers.
        for (block in blocks) {
            val changeStart = block.first
            val changeEndExclusive = block.last + 1

            // Leading context: back up at most contextLines equal ops.
            var lead = 0
            var p = changeStart - 1
            while (p >= 0 && script[p].op == Op.EQUAL && lead < contextLines) {
                lead++
                p--
            }
            val hunkStart = changeStart - lead

            // Trailing context: forward at most contextLines equal ops (clamped to end).
            var trail = 0
            var q = changeEndExclusive
            while (q < script.size && script[q].op == Op.EQUAL && trail < contextLines) {
                trail++
                q++
            }
            val hunkEndExclusive = changeEndExclusive + trail

            // Compute 1-based start line numbers by counting EQUAL/DELETE for the original side
            // and EQUAL/INSERT for the modified side up to hunkStart.
            var origLine = 1
            var newLine = 1
            for (k in 0 until hunkStart) {
                when (script[k].op) {
                    Op.EQUAL -> { origLine++; newLine++ }
                    Op.DELETE -> origLine++
                    Op.INSERT -> newLine++
                }
            }

            hunks.add(
                Hunk(
                    ops = script.subList(hunkStart, hunkEndExclusive),
                    origStart = origLine,
                    newStart = newLine,
                )
            )
        }

        return hunks
    }

    /**
     * Appends a `@@ -oldStart,oldCount +newStart,newCount @@` header for [hunk].
     *
     * Line counts are derived from the hunk's operations (EQUAL/DELETE count toward the original
     * span; EQUAL/INSERT toward the modified span). Git's convention for a pure insertion
     * (oldCount == 0) reports `oldStart` as the line *after* which insertion happens, and for a
     * pure deletion (newCount == 0) reports `newStart` similarly; this is produced naturally
     * because [Hunk.origStart]/[Hunk.newStart] already point at the first op of the hunk.
     *
     * @param sb Builder receiving the header line (terminated by `'\n'`).
     * @param hunk The hunk to describe.
     */
    private fun appendHunkHeader(sb: StringBuilder, hunk: Hunk) {
        var oldCount = 0
        var newCount = 0
        for ((op) in hunk.ops) {
            when (op) {
                Op.EQUAL -> { oldCount++; newCount++ }
                Op.DELETE -> oldCount++
                Op.INSERT -> newCount++
            }
        }
        sb.append("@@ -").append(hunk.origStart).append(',').append(oldCount)
            .append(" +").append(hunk.newStart).append(',').append(newCount)
            .append(" @@\n")
    }
}
