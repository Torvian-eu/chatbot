package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads the charge a provider reported for one generation, exactly as the provider stated it.
 *
 * The charge is an extension some providers add to the usage object of a response, so an element of an unexpected
 * type, a non-finite value, a negative amount and an implausibly large amount all read as "no charge reported"
 * instead of failing the generation that carried them or recording a value that cannot be a real charge. Nothing
 * else is done to the value: no rounding, no rescaling and no unit assigned, because only the provider knows what
 * its own figure means.
 *
 * Only the total the provider charged is read; a provider's separate upstream-inference figure is not, because it
 * is not what the request was billed.
 *
 * @param cost The provider's raw `usage.cost` element, or `null` when it reported none.
 * @return The reported charge, or `null` when the provider reported no usable charge.
 */
internal fun reportedCost(cost: JsonElement?): Double? {
    // doubleOrNull accepts a bare JSON number and a numeric string, and rejects an object, an array and null.
    val amount = (cost as? JsonPrimitive)?.doubleOrNull ?: return null
    if (!amount.isFinite() || amount < 0.0) return null
    if (amount >= MAX_REPORTED_COST) return null
    return amount
}

/**
 * Highest charge accepted from a provider, in the provider's own currency.
 *
 * A charge at or above it is treated as a malformed provider value rather than recorded, which keeps a provider
 * bug from being persisted as a real charge.
 */
private const val MAX_REPORTED_COST: Double = 1_000_000.0
