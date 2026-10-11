package eu.torvian.chatbot.app.compose.chatarea

import kotlin.math.floor

/**
 * Renders a charge as US dollars with a `$` prefix, showing only as many decimals as the amount needs.
 *
 * Every charge a provider reports is displayed, and the reported figure is taken to be US dollars: the only wired
 * provider that states a charge today bills in USD-equivalent credits, so no other currency is expected here. A
 * provider whose charge is not in dollars would be shown under a symbol it did not use, and nothing in the value
 * could reveal that.
 *
 * Amounts of at least one cent are shown with two decimals; smaller amounts widen to six decimals so their leading
 * significant digits survive, and trailing zeros are trimmed but never below two decimals. A strictly positive
 * amount that would still round to zero widens further, up to nine decimals, so a charged request is never
 * presented as free; a reported zero is a real measurement and keeps its zero amount. The arithmetic is
 * integer-based, which keeps the result identical on every platform the app targets.
 *
 * @param cost Charge to render, which is never negative because a charge is either reported by the provider or
 *            absent.
 * @return The charge rendered with a `$` prefix, e.g. `$0.95`, `$0.00` or `$0.000095`.
 */
internal fun formatUsdCost(cost: Double): String {
    var decimals = if (cost >= MIN_TWO_DECIMAL_COST) 2 else 6
    var units = scaledUnits(cost, decimals)

    // One decimal at a time, so the amount is rendered at the coarsest resolution that still shows it rather than
    // at a fixed one; the fallback below then covers an amount finer than the widest resolution.
    while (units == 0L && decimals < MAX_DECIMALS) {
        decimals++
        units = scaledUnits(cost, decimals)
    }

    if (units == 0L && cost > 0.0) units = 1L

    val scale = powerOfTen(decimals)
    val whole = units / scale
    val fraction = (units % scale).toString()
        .padStart(decimals, '0')
        .trimEnd('0')
        .padEnd(2, '0')

    return "$$whole.$fraction"
}

/**
 * Rounds a charge to a whole number of units at the given resolution.
 *
 * The rounding is half-up on a non-negative value, which is what a rendered amount is expected to do; ties are not
 * left to the platform's default tie-breaking.
 *
 * @param cost Charge to scale.
 * @param decimals Decimal places of the target resolution.
 * @return The charge expressed in units of `10^-decimals`.
 */
private fun scaledUnits(cost: Double, decimals: Int): Long =
    floor(cost * powerOfTen(decimals) + 0.5).toLong()

/**
 * Computes a non-negative power of ten as an integer, avoiding any floating-point step.
 *
 * @param exponent Power to raise ten to; must not be negative.
 * @return Ten raised to [exponent].
 */
private fun powerOfTen(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10L }
    return result
}

/** Finest resolution a charge is rendered at, i.e. the largest amount the never-free rendering can overstate. */
private const val MAX_DECIMALS = 9

/** Smallest charge rendered with two decimals; anything below it is rendered with more. */
private const val MIN_TWO_DECIMAL_COST = 0.01
