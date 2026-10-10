package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Aggregate statistics of one thread, computed server-side over the root→leaf branch of a session's
 * current leaf message.
 *
 * A `null` field means "not available" — no measurement was recorded, or the denominator of a derived
 * value is empty. It is never a stand-in for zero: a token counter the branch reported as zero
 * everywhere is a measured `0`, while an unreported counter is `null`. Percentage-like fields are
 * fractions in `[0,1]`; clients are responsible for rendering them as percentages.
 *
 * @property totalMessages Number of messages in the branch.
 * @property userMessages Number of user messages in the branch.
 * @property assistantMessages Number of assistant messages in the branch, including an in-flight one.
 * @property toolCalls Number of tool calls attributed to the branch's messages.
 * @property reasoningItems Sum of the reasoning items of the branch's assistant messages; messages without
 *            reasoning contribute nothing.
 * @property inputTokens Sum of the reported input tokens, or `null` when no assistant message of the
 *            branch reports usage. Messages without usage contribute nothing to a reported sum.
 * @property outputTokens Sum of the reported output tokens, or `null` when no assistant message of the
 *            branch reports usage. Messages without usage contribute nothing to a reported sum.
 * @property reasoningTokens Sum of the reported reasoning tokens, or `null` when no assistant message of
 *            the branch reports that counter. A message that reports none is skipped instead of
 *            contributing zero.
 * @property cachedInputTokens Sum of the reported cached input tokens, or `null` when no assistant
 *            message of the branch reports that counter. A message that reports none is skipped instead
 *            of contributing zero.
 * @property responseDuration Distribution of the measured assistant response durations.
 * @property toolCallDuration Distribution of the measured tool call durations.
 * @property toolCallSuccessRate Fraction of the branch's tool calls whose status is `SUCCESS`, or `null`
 *            when the branch has no tool call.
 * @property assistantMessageSuccessRate Fraction of the branch's assistant messages that completed, or
 *            `null` when the branch has no assistant message. An in-flight message counts as unsuccessful.
 * @property averageOutputTokensPerSecond Output tokens per second of measured provider time, or `null`
 *            when no response duration was measured or no output-token total was reported.
 * @property inputCacheHitRate Fraction of input tokens served from the provider cache, or `null` when the
 *            remaining input-token denominator is empty. The first assistant message carrying usage is
 *            excluded from both sides, because its input can never have been cached.
 */
@Serializable
data class ThreadStats(
    val totalMessages: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val reasoningItems: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val cachedInputTokens: Long?,
    val responseDuration: DurationStats,
    val toolCallDuration: DurationStats,
    val toolCallSuccessRate: Double?,
    val assistantMessageSuccessRate: Double?,
    val averageOutputTokensPerSecond: Double?,
    val inputCacheHitRate: Double?
)

/**
 * Distribution of one family of measured durations, over the subset of items that carry a measurement.
 *
 * @property totalMs Sum of the measured durations, or `null` when nothing was measured; a measured
 *            zero is reported as `0`.
 * @property averageMs Arithmetic mean, or `null` when nothing was measured.
 * @property standardDeviationMs Population standard deviation, or `null` when nothing was measured.
 * @property outliers Identifiers of the measured items whose duration is strictly greater than
 *            `mean + 2σ`, descending by duration and capped at the first 10; empty when there is no such
 *            item. Identifiers are assistant message ids for [ThreadStats.responseDuration] and tool
 *            call ids for [ThreadStats.toolCallDuration].
 */
@Serializable
data class DurationStats(
    val totalMs: Long?,
    val averageMs: Double?,
    val standardDeviationMs: Double?,
    val outliers: List<Long>
)
