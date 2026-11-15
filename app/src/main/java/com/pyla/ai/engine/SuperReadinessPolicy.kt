package com.pyla.ai.engine

/**
 * Read-time migration of legacy Super settings; never rewrites retained user configuration.
 * The historical 1800-unit default maps to the visual detector's existing 25% yellow coverage.
 * Custom legacy rectangles remain hard search constraints; only the known factory rectangle
 * migrates to automatic localization because it misses the control on phone aspect ratios.
 */
class SuperReadinessPolicy private constructor(
    val minimumYellowFraction: Double,
    private val region: List<Int>?,
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun acceptsYellow(yellowPixels: Long, areaPixels: Long): Boolean =
        areaPixels > 0 && yellowPixels in 0..areaPixels &&
            yellowPixels.toDouble() / areaPixels > minimumYellowFraction

    /** Pixel bounds; custom geometry uses the unchanged legacy aspect-aware mapping. */
    fun searchBounds(width: Int, height: Int): Bounds? {
        if (width <= 0 || height <= 0) return null
        val wr = width / 1920.0
        val hr = height / 1080.0
        val s = com.pyla.ai.capture.UiLayout.unit(width, height, 720.0)
        // The control cluster is pinned to the bottom-right corner; on frames narrower than 16:9
        // it keeps its scaled distance from the bottom edge (identical mapping on 16:9 and wider).
        val top = if (com.pyla.ai.capture.UiLayout.isNarrow(width, height)) height - (720.0 - 288.0) * s else 288.0 * s
        val bottom = if (com.pyla.ai.capture.UiLayout.isNarrow(width, height)) height - (720.0 - 710.0) * s else 710.0 * s
        val bounds = if (region == null) {
            Bounds((width - 624.0 * s).toInt().coerceAtLeast(0), top.toInt().coerceAtLeast(0),
                (width - 10.0 * s).toInt().coerceAtLeast(0), bottom.toInt())
        } else {
            Bounds(
                minOf(region[0] * wr, width - (1920 - region[0]) * hr).toInt(),
                (region[1] * hr).toInt(),
                maxOf(region[2] * wr, width - (1920 - region[2]) * hr).toInt(),
                (region[3] * hr).toInt(),
            )
        }
        return bounds.takeIf {
            it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height &&
                it.right > it.left && it.bottom > it.top
        }
    }

    companion object {
        private val FACTORY_REGION = listOf(1460, 830, 1560, 930)

        fun defaults(): SuperReadinessPolicy = SuperReadinessPolicy(0.25, null)

        /** Invalid explicit settings deny detection, rather than silently replacing user limits. */
        fun fromLegacy(threshold: Any?, crop: Any?): SuperReadinessPolicy? {
            val pixels = when (threshold) {
                null -> 1800.0
                is Number -> threshold.toDouble()
                is String -> threshold.toDoubleOrNull() ?: return null
                else -> return null
            }
            if (!pixels.isFinite() || pixels < 0) return null
            val region = if (crop == null) null else {
                val values = crop as? List<*> ?: return null
                if (values.size != 4) return null
                val ints = values.map { value ->
                    val number = (value as? Number)?.toDouble() ?: return null
                    if (!number.isFinite() || number != number.toInt().toDouble()) return null
                    number.toInt()
                }
                if (ints[0] !in 0..1919 || ints[2] !in 1..1920 || ints[0] >= ints[2] ||
                    ints[1] !in 0..1079 || ints[3] !in 1..1080 || ints[1] >= ints[3]) return null
                ints.takeUnless { it == FACTORY_REGION }
            }
            // Do not clamp large configured thresholds: >=7200 intentionally denies all coverage.
            return SuperReadinessPolicy(0.25 * (pixels / 1800.0), region)
        }
    }
}
