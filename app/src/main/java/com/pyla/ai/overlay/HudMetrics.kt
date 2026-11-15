package com.pyla.ai.overlay

/**
 * Formatting rules for the compact HUD. Everything here is pure Kotlin (no Android imports) so the
 * exact strings and thresholds are unit-testable on the JVM.
 *
 * Truth rules:
 *  - a metric that the engine did not measure is rendered as [EM_DASH], never as 0;
 *  - overflowing numbers carry an explicit greater-than marker within the fixed display budget;
 *  - IPS is displayed exactly as measured (completed cycles / wall time), never derived from LOOP.
 */
object HudMetrics {
    const val EM_DASH = "\u2014"
    /**
     * Largest millisecond value shown as a number. It was 999, so a slow device whose loop takes
     * about one second showed a constant ">999ms" instead of its real loop time.
     */
    const val MAX_MS = 9_999.0
    const val MAX_FRAME_AGE_MS = 9_999L
    const val MAX_IPS = 999.9
    const val STYLE_MAX_LENGTH = 12
    const val BACKEND_MAX_LENGTH = 10

    /** Placeholder shared by every optional value. */
    fun placeholder(): String = EM_DASH

    /**
     * A millisecond value as displayed inside the pill: `28 ms`, `8.4 ms`, or `—`.
     * Values below 10 ms keep one decimal because that is where sub-frame differences matter.
     */
    fun ms(value: Double?): String {
        val v = sane(value)?.takeIf { it >= 0.0 } ?: return EM_DASH
        if (v > MAX_MS) return ">9999ms"
        val clamped = v.coerceIn(0.0, MAX_MS)
        val number = if (clamped < 10.0) {
            val tenths = (clamped * 10.0).toLong() / 10.0
            if (tenths % 1.0 == 0.0) tenths.toLong().toString() else String.format(java.util.Locale.US, "%.1f", tenths)
        } else {
            Math.round(clamped).toString()
        }
        // Four digits drop the space ("1123ms") so they fit the column reserved for "888 ms".
        return if (clamped >= 999.5) "${number}ms" else "$number ms"
    }

    /** Frame age is a whole-millisecond Long measurement. */
    fun frameAge(value: Long?): String {
        if (value == null || value < 0L) return EM_DASH
        if (value > MAX_FRAME_AGE_MS) return ">9999ms"
        return "${value.coerceAtMost(MAX_FRAME_AGE_MS)} ms"
    }

    /** Completed processing cycles per second, one decimal, clamped, never inferred from LOOP. */
    fun ips(value: Double?): String {
        val v = sane(value)?.takeIf { it >= 0.0 } ?: return EM_DASH
        if (v > MAX_IPS) return ">999"
        return String.format(java.util.Locale.US, "%.1f", v.coerceIn(0.0, MAX_IPS))
    }

    /** Short, upper-case playstyle label. Blank/missing data stays an em dash. */
    fun style(value: String?): String {
        val normalized = normalize(value) ?: return EM_DASH
        val upper = normalized.uppercase(java.util.Locale.US)
        return clamp(upper, STYLE_MAX_LENGTH)
    }

    /** Backend name as reported by the engine (`NNAPI`, `GPU`, ...). */
    fun backend(value: String?): String {
        val normalized = normalize(value) ?: return EM_DASH
        return clamp(normalized.uppercase(java.util.Locale.US), BACKEND_MAX_LENGTH)
    }

    /** Free-form error text for the optional detail row; newlines and control characters removed. */
    fun error(value: String?): String? = normalize(value)

    /**
     * Threshold verdict for a millisecond metric only. IPS is a rate and deliberately has no
     * threshold helper: colouring a rate with a latency budget would be a lie.
     */
    fun msTone(valueMs: Double?, budgetMs: Double, warnMs: Double): HudTone {
        val v = sane(valueMs) ?: return HudTone.MUTED
        return when {
            v <= budgetMs -> HudTone.GOOD
            v <= warnMs -> HudTone.WARN
            else -> HudTone.BAD
        }
    }

    private fun sane(value: Double?): Double? =
        if (value == null || value.isNaN() || value.isInfinite()) null else value

    private fun normalize(value: String?): String? {
        val collapsed = value?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim().orEmpty()
        return collapsed.ifBlank { null }
    }

    private fun clamp(text: String, maxLength: Int): String =
        if (text.length <= maxLength) text else text.substring(0, (maxLength - 1).coerceAtLeast(0)) + "…"
}
