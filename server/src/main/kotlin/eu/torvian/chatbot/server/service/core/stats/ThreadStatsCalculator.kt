package eu.torvian.chatbot.server.service.core.stats

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.thread.ThreadBranchError
import eu.torvian.chatbot.common.misc.thread.resolveThreadBranch
import eu.torvian.chatbot.common.models.api.core.DurationStats
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlin.math.sqrt

/**
 * Pure computation of one thread's statistics.
 *
 * The calculator performs no I/O: it receives the session's already loaded messages and tool calls and
 * derives every value from them, so the maths is unit-testable without a database. Only the root→leaf
 * branch of the requested leaf message is measured; sibling branches are ignored.
 *
 * Every measurement is read through its `null`-means-absent convention: an item without a recorded value
 * is left out of both the numerator and the denominator of the derived value instead of contributing a
 * zero.
 */
object ThreadStatsCalculator {

    /**
     * Computes the statistics of the branch ending at [currentLeafMessageId].
     *
     * @param sessionMessages All messages currently known for the session.
     * @param currentLeafMessageId Leaf message whose root→leaf branch is measured, or `null` when the
     *            session has no current leaf (the result is then an empty thread).
     * @param sessionToolCalls Persisted tool calls of the session; only those attributed to a message of
     *            the branch are measured.
     * @return The aggregate statistics of the branch.
     * @throws IllegalStateException When the leaf is absent from [sessionMessages], when a parent link
     *         points to a missing message, or when the parent chain is cyclic. Returning a partial thread
     *         would present fabricated numbers as authoritative, so a corrupt chain fails loudly.
     */
    fun compute(
        sessionMessages: List<ChatMessage>,
        currentLeafMessageId: Long?,
        sessionToolCalls: List<ToolCall>
    ): ThreadStats {
        val threadMessages = resolveThread(sessionMessages, currentLeafMessageId)
        val threadMessageIds = threadMessages.mapTo(mutableSetOf()) { it.id }
        val threadToolCalls = sessionToolCalls.filter { it.messageId in threadMessageIds }
        val assistantMessages = threadMessages.filterIsInstance<ChatMessage.AssistantMessage>()

        val responseDuration = durationStats(
            assistantMessages.mapNotNull { message ->
                message.responseDurationMs?.let { message.id to it }
            }
        )
        val toolCallDuration = durationStats(
            threadToolCalls.mapNotNull { toolCall ->
                toolCall.durationMs?.let { toolCall.id to it }
            }
        )

        val outputTokens = sumReported(assistantMessages.mapNotNull { it.usageStats?.outputTokens?.toLong() })
        val measuredResponseMs = responseDuration.totalMs?.takeIf { it > 0L }

        return ThreadStats(
            totalMessages = threadMessages.size,
            userMessages = threadMessages.count { it is ChatMessage.UserMessage },
            assistantMessages = assistantMessages.size,
            toolCalls = threadToolCalls.size,
            reasoningItems = assistantMessages.sumOf { it.reasoningItems?.size ?: 0 },
            inputTokens = sumReported(assistantMessages.mapNotNull { it.usageStats?.inputTokens?.toLong() }),
            outputTokens = outputTokens,
            reasoningTokens = sumReported(
                assistantMessages.mapNotNull { it.usageStats?.reasoningTokens?.toLong() }
            ),
            cachedInputTokens = sumReported(assistantMessages.mapNotNull { it.usageStats?.cachedTokens?.toLong() }),
            cost = sumReported(assistantMessages.mapNotNull { it.usageStats?.cost }),
            responseDuration = responseDuration,
            toolCallDuration = toolCallDuration,
            toolCallSuccessRate = rate(
                count = threadToolCalls.size,
                matches = threadToolCalls.count { it.status == ToolCallStatus.SUCCESS }
            ),
            assistantMessageSuccessRate = rate(
                count = assistantMessages.size,
                matches = assistantMessages.count { it.isComplete }
            ),
            averageOutputTokensPerSecond = measuredResponseMs?.let { measuredMs ->
                outputTokens?.let { it / (measuredMs / MILLIS_PER_SECOND) }
            },
            inputCacheHitRate = inputCacheHitRate(assistantMessages)
        )
    }

    /**
     * Collects the branch ending at [currentLeafMessageId] in chronological order.
     *
     * @param sessionMessages All messages currently known for the session.
     * @param currentLeafMessageId Leaf message to walk up from, or `null` for an empty thread.
     * @return The branch messages from root to leaf, or an empty list when there is no leaf or no message.
     * @throws IllegalStateException When a message of the chain is missing from [sessionMessages] or the
     *         chain revisits a message.
     */
    private fun resolveThread(
        sessionMessages: List<ChatMessage>,
        currentLeafMessageId: Long?
    ): List<ChatMessage> {
        if (currentLeafMessageId == null || sessionMessages.isEmpty()) return emptyList()

        return resolveThreadBranch(sessionMessages.associateBy { it.id }, currentLeafMessageId)
            .getOrElse { error ->
                throw IllegalStateException(
                    when (error) {
                        is ThreadBranchError.LeafNotFound ->
                            "Cannot compute thread stats: message ${error.leafId} does not exist in the session"

                        is ThreadBranchError.BrokenChain ->
                            "Cannot compute thread stats: message ${error.parentId} does not exist in the session"

                        is ThreadBranchError.CyclicChain ->
                            "Cannot compute thread stats: cyclic parent chain detected at message " +
                                "${error.messageId}"
                    }
                )
            }
    }

    /**
     * Summarises the measured durations of one family of items.
     *
     * @param measured Item id paired with its measured duration in milliseconds.
     * @return The total, arithmetic mean, population standard deviation and outlier ids of [measured];
     *         every value is `null` or empty when nothing was measured.
     */
    private fun durationStats(measured: List<Pair<Long, Long>>): DurationStats {
        if (measured.isEmpty()) {
            return DurationStats(
                totalMs = null,
                averageMs = null,
                standardDeviationMs = null,
                outliers = emptyList()
            )
        }

        val total = measured.sumOf { it.second }
        val average = total.toDouble() / measured.size
        // Population variance (divide by N): the measured set is the whole family, not a sample of it.
        val variance = measured.sumOf { (it.second - average) * (it.second - average) } / measured.size
        val standardDeviation = sqrt(variance)
        val outlierThreshold = average + OUTLIER_STANDARD_DEVIATIONS * standardDeviation

        return DurationStats(
            totalMs = total,
            averageMs = average,
            standardDeviationMs = standardDeviation,
            // Only unusually slow items are outliers; strictly above the threshold, longest first.
            outliers = measured
                .filter { it.second > outlierThreshold }
                .sortedByDescending { it.second }
                .take(MAX_OUTLIERS)
                .map { it.first }
        )
    }

    /**
     * Sums the counters a branch actually reported.
     *
     * @param reported Values of the messages that report the counter; messages that omit it contribute
     *            nothing.
     * @return The sum of [reported], or `null` when no message reported the counter. A sum of reported
     *         zeros is a measured `0`.
     */
    private fun sumReported(reported: List<Long>): Long? =
        if (reported.isEmpty()) null else reported.sum()

    /**
     * Sums the charges a branch actually reported.
     *
     * @param reported Charges of the messages that report one; messages that report none contribute
     *            nothing.
     * @return The sum of [reported], or `null` when no message reported a charge. A sum of reported
     *         zeros is a measured `0`.
     */
    private fun sumReported(reported: List<Double>): Double? =
        if (reported.isEmpty()) null else reported.sum()

    /**
     * Computes the share of input tokens the provider served from its cache.
     *
     * The first assistant message that reports usage is left out of both sides of the ratio: its prompt
     * cannot have been cached yet, so including it would understate the hit rate of the rest of the
     * thread.
     *
     * @param assistantMessages Branch assistant messages in chronological order.
     * @return The fraction of cached input tokens, or `null` when no remaining message reports input
     *         tokens. A message that omits the cached counter is counted as contributing none, while an
     *         input total of zero leaves the rate undefined.
     */
    private fun inputCacheHitRate(assistantMessages: List<ChatMessage.AssistantMessage>): Double? {
        val cacheCandidates = assistantMessages
            .dropWhile { it.usageStats == null }
            .drop(1)
            .mapNotNull { it.usageStats }

        val inputTotal = cacheCandidates.sumOf { it.inputTokens.toLong() }
        if (inputTotal == 0L) return null

        val cachedTotal = cacheCandidates.sumOf { it.cachedTokens?.toLong() ?: 0L }
        return cachedTotal.toDouble() / inputTotal
    }

    /**
     * Computes a success fraction over a set of items.
     *
     * @param count Size of the measured set.
     * @param matches Number of items in the set considered successful.
     * @return The fraction of successful items, or `null` when the set is empty.
     */
    private fun rate(count: Int, matches: Int): Double? =
        if (count == 0) null else matches.toDouble() / count

    /** Outlier threshold in standard deviations above the mean. */
    private const val OUTLIER_STANDARD_DEVIATIONS = 2.0

    /** Highest number of outlier ids reported per duration family. */
    private const val MAX_OUTLIERS = 10

    /** Milliseconds per second, used to express the measured time in seconds. */
    private const val MILLIS_PER_SECOND = 1000.0
}
