package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTunerTest {
    @Test fun moreThreadsAreRejectedWhenTheyAreSlower() {
        // Measured on the 16:9 test device: 1 thread 262 ms, 2 threads 228 ms, 3 threads 332 ms.
        val asked = ArrayList<Int>()
        val choice = InferenceTuner.climb(listOf(1, 2, 3, 4)) { asked.add(it); mapOf(1 to 262L, 2 to 228L, 3 to 332L, 4 to 364L)[it] }!!
        assertEquals(2, choice.key)
        assertEquals(228L, choice.medianMs)
        assertEquals(listOf(1, 2, 3), asked) // stops after the first non-improvement
    }

    @Test fun noiseLevelGainDoesNotAddThreads() {
        val choice = InferenceTuner.climb(listOf(1, 2, 3)) { mapOf(1 to 100L, 2 to 97L, 3 to 50L)[it] }!!
        assertEquals(1, choice.key)
    }

    @Test fun failedCandidatesAreSkippedAndNeverChosen() {
        val choice = InferenceTuner.climb(listOf(1, 2, 3)) { if (it == 1) null else if (it == 2) throw IllegalStateException() else 80L }!!
        assertEquals(3, choice.key)
        assertNull(InferenceTuner.climb(listOf(1, 2)) { null })
        assertNull(InferenceTuner.climb(emptyList<Int>()) { 1L })
    }

    @Test fun alternativeProviderMustBeARealImprovement() {
        assertFalse(InferenceTuner.preferAlternative(249L, 762L)) // XNNPACK vs NNAPI on the test device
        assertTrue(InferenceTuner.preferAlternative(249L, 40L))
        assertFalse(InferenceTuner.preferAlternative(249L, 240L))
        assertFalse(InferenceTuner.preferAlternative(249L, null))
        assertFalse(InferenceTuner.preferAlternative(249L, -1L))
    }

    @Test fun steadyMedianDropsTheWarmUp() {
        assertEquals(100L, InferenceTuner.steadyMedian(listOf(900L, 100L, 100L)))
        assertEquals(120L, InferenceTuner.steadyMedian(listOf(900L, 90L, 120L)))
        assertEquals(7L, InferenceTuner.steadyMedian(listOf(7L)))
        assertNull(InferenceTuner.steadyMedian(emptyList()))
    }

    @Test fun threadCandidatesAreBoundedByCores() {
        assertEquals(listOf(1), InferenceTuner.threadCandidates(1))
        assertEquals(listOf(1, 2, 3, 4), InferenceTuner.threadCandidates(8))
        assertEquals(listOf(1), InferenceTuner.threadCandidates(0))
    }

    @Test fun cheapTerrainKeepsTheConfiguredInterval() {
        assertEquals(0.1, InferenceTuner.terrainIntervalSec(0.1, 0L), 0.0)
        assertEquals(0.1, InferenceTuner.terrainIntervalSec(0.1, 100L), 0.0)
    }

    @Test fun expensiveTerrainIsStretchedAndBounded() {
        assertEquals(1.0, InferenceTuner.terrainIntervalSec(0.1, 250L), 1e-9)
        assertEquals(2.0, InferenceTuner.terrainIntervalSec(0.1, 762L), 1e-9)
        assertEquals(3.0, InferenceTuner.terrainIntervalSec(3.0, 762L), 1e-9) // never shorter than configured
    }

    @Test fun attackTemplateBudgetEscalatesOnlyToItsBound() {
        val base = TemplateAttackControl.TIMEOUT_NANOS
        assertEquals(base, TemplateAttackControl.currentBudgetNanos())
        assertEquals(2 * base, TemplateAttackControl.escalatedBudget(base))
        assertEquals(4 * base, TemplateAttackControl.escalatedBudget(2 * base))
        assertEquals(TemplateAttackControl.MAX_TIMEOUT_NANOS, TemplateAttackControl.escalatedBudget(4 * base))
        assertEquals(TemplateAttackControl.MAX_TIMEOUT_NANOS,
            TemplateAttackControl.escalatedBudget(TemplateAttackControl.MAX_TIMEOUT_NANOS))
        assertEquals(2 * base, TemplateAttackControl.escalatedBudget(0L)) // never below the base
    }

    @Test fun terrainIsDeferredOnlyOnSlowDevicesWhileTheControlSearchIsPending() {
        assertFalse(InferenceTuner.deferTerrain(true, InferenceTuner.CHEAP_TERRAIN_MS, 0)) // fast device: unchanged
        assertFalse(InferenceTuner.deferTerrain(false, 1500, 0)) // control already learned
        assertTrue(InferenceTuner.deferTerrain(true, 1500, 0))
        assertTrue(InferenceTuner.deferTerrain(true, 1500, InferenceTuner.MAX_TERRAIN_DEFERRALS - 1))
        assertFalse(InferenceTuner.deferTerrain(true, 1500, InferenceTuner.MAX_TERRAIN_DEFERRALS)) // never starved
    }

    @Test fun menuVerdictIsReusedOnlyOnSlowDevicesAndNeverForAMenu() {
        assertFalse(InferenceTuner.reuseMenuVerdict(InferenceTuner.CHEAP_CLASSIFY_MS, false, 500, 0)) // fast device
        assertTrue(InferenceTuner.reuseMenuVerdict(700, false, 500, 0))
        assertFalse(InferenceTuner.reuseMenuVerdict(700, true, 500, 0)) // a menu is always re-checked
        assertFalse(InferenceTuner.reuseMenuVerdict(700, false, InferenceTuner.MENU_VERDICT_MAX_AGE_MS + 1, 0))
        assertFalse(InferenceTuner.reuseMenuVerdict(700, false, -1, 0)) // older frame
        assertFalse(InferenceTuner.reuseMenuVerdict(700, false, 500, 1)) // never twice in a row
    }

    @Test fun variantsFitByAspectRatio() {
        fun names(w: Int, h: Int) = EntityModelVariants.candidates(w, h).map { it.file }
        val all = listOf("entity_640x384.onnx", "entity_512x288.onnx", "entity_448x256.onnx")
        assertEquals(all, names(1280, 720))   // 16:9
        assertEquals(all, names(1136, 640))
        assertEquals(all, names(1560, 720))   // 19.5:9 reference phone
        assertEquals(all, names(1600, 720))   // 20:9
        assertEquals(listOf("entity_640x384.onnx"), names(1152, 690)) // 5:3 fits 384 rows only
        assertEquals(emptyList<String>(), names(1152, 720)) // 16:10 tablet keeps the factory model
        assertEquals(emptyList<String>(), names(960, 720))  // 4:3
        assertEquals(emptyList<String>(), names(720, 1280)) // portrait
        assertEquals(emptyList<String>(), names(0, 0))
    }
}
