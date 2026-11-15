package com.pyla.ai.engine

/**
 * Pure selection logic for inference execution settings. No Android, ORT or clock dependency:
 * the caller supplies a measurement function, so the policy is unit-testable.
 *
 * Why this exists: thread count and execution provider were fixed heuristics tuned on one
 * flagship phone. On other SoCs more intra-op threads can be *slower* (the game itself occupies
 * the cores) and NNAPI can be several times slower than XNNPACK. Measuring on the actual device,
 * while the game is running, is the only setting that holds everywhere.
 */
object InferenceTuner {

    /** A candidate must beat the incumbent by this factor to replace it (noise guard). */
    const val IMPROVEMENT = 0.92

    /** Result of a selection: the winning key and every successful measurement in ms. */
    data class Choice<K>(val key: K, val medianMs: Long, val measured: Map<K, Long>)

    /**
     * Greedy climb over an ordered candidate list (cheapest configuration first).
     *
     * Stops at the first candidate that is not a real improvement over the best so far, so a
     * slow device pays for at most one wasted measurement. A candidate whose measurement fails
     * (`null`) is skipped and never selected. Returns `null` when nothing could be measured.
     */
    fun <K> climb(candidates: List<K>, measure: (K) -> Long?): Choice<K>? {
        var best: K? = null
        var bestMs = Long.MAX_VALUE
        val measured = LinkedHashMap<K, Long>()
        for (candidate in candidates) {
            val ms = try { measure(candidate) } catch (_: Throwable) { null }
            if (ms == null || ms < 0L) continue
            measured[candidate] = ms
            if (best == null) {
                best = candidate; bestMs = ms
                continue
            }
            if (ms.toDouble() <= bestMs * IMPROVEMENT) {
                best = candidate; bestMs = ms
            } else {
                break
            }
        }
        val winner = best ?: return null
        return Choice(winner, bestMs, measured)
    }

    /**
     * Picks between an incumbent and one alternative (for example XNNPACK vs NNAPI): the
     * alternative wins only when it is a real improvement, and never when it failed to measure.
     */
    fun preferAlternative(incumbentMs: Long, alternativeMs: Long?): Boolean =
        alternativeMs != null && alternativeMs >= 0L && alternativeMs.toDouble() <= incumbentMs * IMPROVEMENT

    /** Median of the measurements after dropping the first (warm-up) sample when possible. */
    fun steadyMedian(samplesMs: List<Long>): Long? {
        if (samplesMs.isEmpty()) return null
        val steady = if (samplesMs.size > 1) samplesMs.drop(1) else samplesMs
        val sorted = steady.sorted()
        return sorted[sorted.size / 2]
    }

    /** Ordered thread candidates: 1, 2, ... up to [limit] and never more than the core count. */
    fun threadCandidates(cores: Int, limit: Int = 4): List<Int> =
        (1..minOf(limit, cores.coerceAtLeast(1))).toList()

    /**
     * Terrain refresh interval. The configured interval is kept untouched while terrain inference
     * is cheap (the tested flagship path); on a device where one terrain pass is expensive it is
     * stretched so that terrain uses at most roughly a quarter of the gameplay loop.
     */
    fun terrainIntervalSec(configuredSec: Double, lastCostMs: Long): Double {
        if (lastCostMs <= CHEAP_TERRAIN_MS) return configuredSec
        return maxOf(configuredSec, minOf(MAX_TERRAIN_INTERVAL_SEC, lastCostMs * 4 / 1000.0))
    }

    /**
     * Whether this loop should skip its terrain refresh because the one-off attack-control search
     * still has to run in it. On a slow device both together (measured: ~1.5 s terrain plus
     * 0.5-0.8 s search) exceed the 3 s freshness window of the gameplay admission, so admission
     * was lost again before the control was ever learned. Never defers while terrain is cheap,
     * and gives up after [MAX_TERRAIN_DEFERRALS] loops so terrain cannot be starved.
     */
    fun deferTerrain(controlSearchPending: Boolean, expectedTerrainMs: Long, deferredLoops: Int): Boolean =
        controlSearchPending && expectedTerrainMs > CHEAP_TERRAIN_MS && deferredLoops < MAX_TERRAIN_DEFERRALS

    /**
     * Whether the previous in-loop menu classification may stand in for this frame. Never on a
     * device where classifying is cheap (the tested flagship path classifies every frame exactly
     * as before). On a slow device the ~35 template correlations were measured at 0.6-1.1 s per
     * loop while the game is running, which alone pushed consecutive player observations past
     * the 3 s admission window. Only a "no menu" verdict from a frame captured at most
     * [MENU_VERDICT_MAX_AGE_MS] before this one is reused, and never twice in a row, so a menu
     * is still noticed within one loop - sooner than the slow loop noticed it before.
     */
    fun reuseMenuVerdict(lastCostMs: Long, lastWasMenu: Boolean, frameGapMs: Long, reusedInARow: Int): Boolean =
        lastCostMs > CHEAP_CLASSIFY_MS && !lastWasMenu && frameGapMs in 0..MENU_VERDICT_MAX_AGE_MS && reusedInARow < 1

    const val CHEAP_CLASSIFY_MS = 150L
    const val MENU_VERDICT_MAX_AGE_MS = 1_500L
    const val MAX_TERRAIN_DEFERRALS = 12
    const val CHEAP_TERRAIN_MS = 100L
    const val MAX_TERRAIN_INTERVAL_SEC = 2.0
}
