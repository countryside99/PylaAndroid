package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudMetricsTest {
    @Test fun missingValuesAreEmDashesNeverZero() {
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ms(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ms(Double.NaN))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ms(Double.POSITIVE_INFINITY))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ips(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.frameAge(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.style(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.backend(null))
    }

    @Test fun innerLoopValuesKeepOneDecimalAndDropTrailingZero() {
        assertEquals("8.4 ms", HudMetrics.ms(8.44))
        assertEquals("8 ms", HudMetrics.ms(8.0))
        assertEquals("2.5 ms", HudMetrics.ms(2.5))
    }

    @Test fun wholeMillisecondsAreRounded() {
        assertEquals("28 ms", HudMetrics.ms(28.4))
        assertEquals("18 ms", HudMetrics.ms(17.6))
        assertEquals("0 ms", HudMetrics.ms(0.0))
    }

    @Test fun overflowMarkersFitTheFixedColumnBudget() {
        assertEquals(">9999ms", HudMetrics.ms(50_000.0))
        assertEquals(">9999ms", HudMetrics.frameAge(20_000L))
        assertEquals(">999", HudMetrics.ips(4_000.0))
    }

    @Test fun overflowIsMarkedInsteadOfPretendingToBeAnExactLowerMeasurement() {
        assertEquals("1500ms", HudMetrics.ms(1_500.0))
        assertEquals("1123ms", HudMetrics.ms(1_123.4))
        assertEquals("9999ms", HudMetrics.ms(9_999.0))
        assertEquals(">9999ms", HudMetrics.ms(10_000.0))
        assertEquals(">9999ms", HudMetrics.frameAge(12_000L))
        assertEquals(">999", HudMetrics.ips(1_000.0))
        assertEquals("999 ms", HudMetrics.ms(999.0))
        assertEquals("9999 ms", HudMetrics.frameAge(9_999L))
        assertEquals("999.9", HudMetrics.ips(999.9))
    }

    @Test fun negativeSamplesAreTreatedAsUnavailable() {
        // A negative duration is not a measurement; showing 0 would fabricate data.
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ms(-1.0))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.frameAge(-4L))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ips(-2.0))
    }

    @Test fun ipsKeepsOneDecimalSoRatesStayComparable() {
        assertEquals("35.7", HudMetrics.ips(35.74))
        assertEquals("0.0", HudMetrics.ips(0.0))
        assertEquals("12.0", HudMetrics.ips(12.0))
    }

    @Test fun playstyleIsShortUpperCasedAndSingleLine() {
        assertEquals("RUSH", HudMetrics.style("rush"))
        assertEquals("BRAWL BALL", HudMetrics.style("  brawl\n\tball  "))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.style("   "))
    }

    @Test fun overlongPlaystyleIsBoundedForThePill() {
        val rendered = HudMetrics.style("Defensive counter push")
        assertEquals(HudMetrics.STYLE_MAX_LENGTH, rendered.length)
        assertTrue(rendered.endsWith("…"))
        assertTrue(rendered.startsWith("DEFENSIVE"))
    }

    @Test fun backendIsUpperCasedAndBounded() {
        assertEquals("NNAPI", HudMetrics.backend("nnapi"))
        assertEquals("GPU", HudMetrics.backend(" Gpu "))
        assertEquals(HudMetrics.BACKEND_MAX_LENGTH, HudMetrics.backend("a-very-long-backend-name").length)
    }

    @Test fun errorTextIsFlattenedForTheDetailRow() {
        assertEquals("Guard rejected at frame 12", HudMetrics.error("Guard\n rejected\tat frame 12"))
        assertEquals(null, HudMetrics.error("   "))
        assertEquals(null, HudMetrics.error(null))
    }

    @Test fun thresholdsApplyToMillisecondsOnly() {
        assertEquals(HudTone.GOOD, HudMetrics.msTone(18.0, budgetMs = 25.0, warnMs = 50.0))
        assertEquals(HudTone.WARN, HudMetrics.msTone(40.0, budgetMs = 25.0, warnMs = 50.0))
        assertEquals(HudTone.BAD, HudMetrics.msTone(80.0, budgetMs = 25.0, warnMs = 50.0))
        assertEquals(HudTone.MUTED, HudMetrics.msTone(null, budgetMs = 25.0, warnMs = 50.0))
    }
}
