package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable

/**
 * What a provider reported for a single generation: its token counters and, when the provider states one, the
 * charge it billed for that generation.
 *
 * The values are the provider's own and are never synthesized: a generation whose provider reported neither a
 * counter nor a charge carries no [UsageStats] at all instead of a zero-filled object, and an optional value the
 * provider omitted stays `null` instead of being recorded as zero. A charge the provider reported as zero is
 * reported data and is kept as `0`.
 *
 * The three token counters are required and cannot express "not reported", so they read `0` when the provider
 * omitted them. A provider that states only a charge therefore yields a usage whose token total is a zero the
 * provider never reported, not an unmeasured one.
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
 * @property cost Charge the provider reported for this generation, stored exactly as the provider stated it and
 *            denominated in that provider's own currency, which the value itself does not identify, or `null`
 *            when it reports no charge. Only the total charged amount is recorded, never a provider's upstream
 *            inference cost.
 */
@Serializable
data class UsageStats(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cost: Double? = null
)
