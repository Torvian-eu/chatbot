package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlin.time.Instant

/**
 * Finds the retained chunk with the largest eligible coverage for the current source thread.
 *
 * A chunk is eligible only when its coverage is non-empty, its row count matches its declared
 * [ConversationCompactionChunk.coverageCount], its ordinals are contiguous starting at 0, every
 * covered message ID exists in the current thread, every recorded timestamp matches the current
 * source snapshot at millisecond precision, and the covered positions are strictly increasing and
 * contiguous. Branch exclusion, message deletion, and message edits all naturally make a chunk
 * ineligible without mutating or deleting the retained row.
 *
 * Among the eligible chunks the one covering the most source message ids wins: because persisted
 * chunks are cumulative prefixes, the largest coverage is the newest super-set and supersedes every
 * smaller eligible chunk, so a single selection fully determines the window/ledger seed. Ties — two
 * chunks with equal coverage, e.g. a re-compaction of the same prefix — break by newest `(createdAt,
 * id)` so the freshest summary wins deterministically. Older overlapping rows are never deleted and
 * remain available on branches where the largest chunk is ineligible.
 *
 * @param chunks All retained chunks for the session.
 * @param sourceContext The current identity-bearing source thread.
 * @return The largest eligible [ConversationCompactionChunk], or null when no chunk is eligible.
 */
fun findLargestEligibleChunk(
    chunks: List<ConversationCompactionChunk>,
    sourceContext: ConversationContext
): ConversationCompactionChunk? {
    // (threadIndex to updatedAt) indexed by message ID; the map is intentionally a snapshot of the
    // in-memory source thread, so no per-step DB timestamp scan is needed.
    val threadById = sourceContext.units
        .mapIndexed { index, unit -> unit.source.id to (index to unit.source.updatedAt) }
        .toMap()

    return chunks.filter { chunk -> isEligibleChunk(chunk, threadById) }
        .maxWithOrNull(
            // maxWith picks the greatest element per this ascending comparator: largest coverage,
            // then newest (createdAt, id) for equal coverage.
            compareBy<ConversationCompactionChunk> { it.coverage.size }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
}

/**
 * Decides whether a retained chunk is eligible for the current source thread snapshot.
 *
 * A chunk is eligible only when its coverage is non-empty, its row count matches its declared
 * [ConversationCompactionChunk.coverageCount], its ordinals are contiguous starting at 0, every
 * covered message ID exists in the current thread, every recorded timestamp matches the current
 * source snapshot at millisecond precision, and the covered positions are strictly increasing and
 * contiguous. Branch exclusion, message deletion, and message edits all naturally make a chunk
 * ineligible without mutating or deleting the retained row.
 *
 * @param chunk The retained chunk to validate.
 * @param threadById Source thread indexed by message ID: `messageId to (threadIndex, updatedAt)`.
 * @return True when the chunk may seed the rolling window, false when malformed or ineligible.
 */
private fun isEligibleChunk(
    chunk: ConversationCompactionChunk,
    threadById: Map<Long, Pair<Int, Instant>>
): Boolean {
    val coverage = chunk.coverage.sortedBy { it.ordinal }

    // Malformed or legacy coverage must never be selected: it could split provider transcript
    // units or reference a nonexistent ordinal range.
    if (coverage.isEmpty()) return false
    if (coverage.size != chunk.coverageCount) return false
    if (coverage.map { it.ordinal } != coverage.indices.toList()) return false

    val positions = mutableListOf<Int>()
    for ((_, messageId, observedUpdatedAt) in coverage) {
        val current = threadById[messageId] ?: return false
        if (current.second != observedUpdatedAt) return false
        positions.add(current.first)
    }
    if (positions.zipWithNext().any { (first, second) -> first >= second }) return false
    if (positions.last() - positions.first() + 1 != positions.size) return false

    return true
}

/**
 * Builds the auxiliary compaction input for the one-shot rolling-window compaction.
 *
 * The input is the entire over-threshold window in thread order: the prior labeled summary (when one
 * exists) followed by every uncompressed unit's raw messages. Unit boundaries are never split, so an
 * assistant tool call and its reconstructed results are summarized together. Opaque reasoning items
 * are intentionally retained verbatim: they can inform the summary, and the auxiliary model is the
 * one that produced the window content. No primary system prompt and no tools are included; the
 * user's compaction instruction is appended by the service as the final user message of the
 * auxiliary request, not sent as a system message.
 *
 * **Bounded-input guarantee:** because the window is re-verified before every primary call, it crosses
 * the threshold by at most the content appended since the previous preflight — at most one new unit
 * per tool-loop iteration. The input is therefore ≈ threshold + the newest appended unit(s) in the
 * steady state; a single message larger than the threshold is the acknowledged exception, and the
 * first preflight of a thread already over the threshold with no eligible prior chunk is a documented
 * one-time full-thread cost.
 *
 * @param summary The current labeled summary message, or null when nothing has been compacted yet
 *            (first compaction of a thread). Passed verbatim as the first input message.
 * @param units The uncompressed window units in thread order.
 * @return The auxiliary request messages in chronological order.
 */
fun buildCompactionInput(
    summary: RawChatMessage.User?,
    units: List<ConversationContextUnit>
): List<RawChatMessage> {
    val result = mutableListOf<RawChatMessage>()
    if (summary != null) result.add(summary)
    units.forEach { unit -> result.addAll(unit.rawMessages) }
    return result
}
