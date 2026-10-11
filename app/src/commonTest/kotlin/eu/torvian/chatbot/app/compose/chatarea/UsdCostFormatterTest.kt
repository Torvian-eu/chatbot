package eu.torvian.chatbot.app.compose.chatarea

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks the fraction-aware rendering of a reported charge: two decimals from a cent upwards, up to six below,
 * trimmed but never below two, and never a zero amount for a strictly positive charge.
 */
class UsdCostFormatterTest {

    /** Verifies the scale groups and the trimming on ordinary amounts. */
    @Test
    fun formatsOrdinaryAmounts() {
        assertEquals("$0.95", formatUsdCost(0.95))
        assertEquals("$1.50", formatUsdCost(1.5))
        assertEquals("$1.23", formatUsdCost(1.23456789))
        assertEquals("$250.00", formatUsdCost(250.0))
    }

    /** Verifies a charge the provider reported as zero renders as a zero amount. */
    @Test
    fun formatsReportedZero() {
        assertEquals("$0.00", formatUsdCost(0.0))
    }

    /** Verifies amounts below a cent keep their leading significant digits. */
    @Test
    fun keepsSubCentPrecision() {
        assertEquals("$0.01", formatUsdCost(0.01))
        assertEquals("$0.009", formatUsdCost(0.009))
        assertEquals("$0.00099", formatUsdCost(0.00099))
        assertEquals("$0.001", formatUsdCost(0.001))
        assertEquals("$0.000095", formatUsdCost(0.000095))
        assertEquals("$0.0001", formatUsdCost(0.0001))
        assertEquals("$0.000247", formatUsdCost(0.0002466))
        assertEquals("$0.0005", formatUsdCost(0.0005))
    }

    /** Verifies a positive charge below the display precision widens instead of rendering as a free request. */
    @Test
    fun neverCollapsesAPositiveChargeToZero() {
        assertEquals("$0.0000005", formatUsdCost(0.000000499))
        assertEquals("$0.000000001", formatUsdCost(0.000000001))
        // Finer than the widest rendering: the smallest visible unit is shown rather than a zero.
        assertEquals("$0.000000001", formatUsdCost(0.0000000005))
    }

    /** Verifies rounding is half-up and can carry into the next cent, dollar or displayed digit. */
    @Test
    fun roundsHalfUpAcrossGroupBoundaries() {
        assertEquals("$0.01", formatUsdCost(0.014999999))
        assertEquals("$0.02", formatUsdCost(0.015))
        // Just below a cent the six-decimal rendering rounds up to the cent, so the amount stays visible.
        assertEquals("$0.01", formatUsdCost(0.009999999))
        assertEquals("$1.50", formatUsdCost(1.504999999))
        assertEquals("$1.51", formatUsdCost(1.505))
        assertEquals("$1.00", formatUsdCost(0.999999999))
        assertEquals("$0.000001", formatUsdCost(0.0000005))
    }

    /** Verifies trailing zeros are trimmed while at least two decimals are always shown. */
    @Test
    fun trimsTrailingZerosButKeepsTwoDecimals() {
        assertEquals("$1.00", formatUsdCost(1.0))
        assertEquals("$1.05", formatUsdCost(1.05))
        assertEquals("$1.10", formatUsdCost(1.1))
    }
}
