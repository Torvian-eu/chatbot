package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable

/**
 * Token usage a provider reported for a single generation.
 *
 * The counters are the provider's own values and are never synthesized: a generation whose provider reported no
 * usage carries no [UsageStats] at all instead of a zero-filled object, and an optional counter the provider
 * omitted stays `null` instead of being recorded as zero.
 *
 * @property inputTokens Tokens the provider counted for the request input.
 * @property outputTokens Tokens the provider counted for the generated output.
 * @property totalTokens Provider-reported total, or the sum of [inputTokens] and [outputTokens] when the provider
 *            reports no total of its own.
 * @property reasoningTokens Tokens the provider counted for the model's reasoning, or `null` when it reports no
 *            such counter. A provider that reports no reasoning counter must not be read as "zero reasoning
 *            tokens".
 * @property cachedTokens Input tokens the provider served from its prompt cache, or `null` when it reports no such
 *            counter.
 * @property cacheWriteTokens Input tokens the provider wrote into its prompt cache, or `null` when it reports no
 *            such counter.
 */
@Serializable
data class UsageStats(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null
)
