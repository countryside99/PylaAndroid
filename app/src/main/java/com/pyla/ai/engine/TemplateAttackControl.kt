package com.pyla.ai.engine

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounded status of ONE template recognition call. Observation evidence only: no status grants
 * input authority, and only [SINGLE] carries a point.
 */
internal enum class TemplateControlStatus { NONE, SINGLE, AMBIGUOUS, EXHAUSTED, ERROR }

/**
 * A template match mapped back to capture-pixel coordinates. The radius is a template-scale
 * estimate, not a fitted physical edge.
 */
internal data class TemplateControlCandidate(
    val x: Float,
    val y: Float,
    val radius: Float,
    val score: Float,
) {
    val usable: Boolean
        get() = x.isFinite() && y.isFinite() && radius.isFinite() && score.isFinite() && radius > 0f
}

/**
 * Immutable result of one template recognition. Defaults are the "nothing found, nothing spent"
 * value so tests and callers can construct an empty observation without touching native code.
 */
internal data class TemplateControlResult(
    val status: TemplateControlStatus = TemplateControlStatus.NONE,
    val candidate: TemplateControlCandidate? = null,
    val candidates: Int = 0,
    val peakScore: Float = 0f,
    val nanos: Long = 0L,
)

/**
 * Crosshair-template recognizer for the translucent/resting attack control: the fallback evidence
 * path used when the saturated-red and warm-ring paths see no control.
 *
 * Representation (frozen before evaluation; development calibration, NOT blind validation):
 *  - frame: RGB2GRAY, whole-frame normalize to at most 960x540 with INTER_AREA, float/255,
 *    Gaussian sigma 0.7, Sobel 3x3 gx/gy, gradient energy ge = gx^2 + gy^2;
 *  - template: user-traced 127x131 mask, `1 - mask`, per-scale Gaussian sigma 0.7 + Sobel 3x3 at
 *    21 scales `0.18 * 1.1^i` (rounded to 6 decimals), resized INTER_AREA below 1.0 and
 *    INTER_LINEAR otherwise; support mask is `dilate(edge, 3x3 ones)`;
 *  - score: signed gradient CCORR `(match(gx,tx)+match(gy,ty)) / sqrt(match(ge,mask)*sum(tx^2+ty^2))`,
 *    with the float32 cancellation floor applied (near-flat windows are invalid, not huge scores);
 *  - operating point 0.60: a development calibration of this TEST build, deliberately above the
 *    offline prototype's permissive 0.35. It is not a validated false-positive rate.
 *
 * Bounded work: at most 21 scales, at most 4096 raw peaks, at most 128 kept candidates, and a
 * 400 ms wall budget checked between scales and before publishing a result. Exhausting any bound yields
 * [TemplateControlStatus.EXHAUSTED] and no point: missing scales are never hidden and the
 * best-so-far is never selected as a hit.
 *
 * User-authorized search region: lower-right quadrant, with whole template footprints inside it.
 * Matches map back to full capture coordinates; there is no remembered button position.
 * No history or colour training. All per-call native buffers
 * are released in `finally`. The only retained native state is the bounded, immutable template
 * bank (21 scales of small float Mats) built under a lock on first use.
 */
internal object TemplateAttackControl {

    /** Development calibration of the TEST build; above the offline prototype's 0.35. */
    internal const val THRESHOLD = 0.60f
    internal const val SCALE_COUNT = 21
    internal const val BASE_SCALE = 0.18
    internal const val SCALE_STEP = 1.1
    internal const val CANDIDATE_CAP = 128
    internal const val RAW_PEAK_CAP = 4096
    internal const val TIMEOUT_NANOS = 400_000_000L

    /**
     * Upper bound of the adaptive wall budget. The search is a one-off cost: once it completes,
     * the saved calibration removes repeated matching. On a slow CPU the full 21-scale search was
     * measured at 440-530 ms, i.e. it exhausted the fixed 400 ms budget on every single frame,
     * never produced a point and was repeated forever. A device that finishes inside
     * [TIMEOUT_NANOS] never sees a different budget.
     */
    internal const val MAX_TIMEOUT_NANOS = 1_600_000_000L

    @Volatile
    private var budgetNanos: Long = TIMEOUT_NANOS

    /** Doubles the budget after a time exhaustion, bounded by [MAX_TIMEOUT_NANOS]. */
    internal fun escalatedBudget(current: Long): Long =
        min(MAX_TIMEOUT_NANOS, max(TIMEOUT_NANOS, current) * 2)

    internal fun currentBudgetNanos(): Long = budgetNanos

    internal fun resetBudgetForTesting() { budgetNanos = TIMEOUT_NANOS }

    private const val MAX_FRAME_WIDTH = 960
    private const val MAX_FRAME_HEIGHT = 540
    private const val GAUSSIAN_SIGMA = 0.7
    private const val EDGE_FLOOR = 1e-5
    private const val EPS_F32 = 1.1920928955078125e-7f
    private const val SCORE_SLACK = 1.001f

    /** Template anchor relative to the cropped template, in template pixels at scale 1. */
    private const val TEMPLATE_CENTER_X = 63.5
    private const val TEMPLATE_CENTER_Y = 64.5

    /** Template-scale estimate of the control radius; deliberately not a fitted physical edge. */
    private const val TEMPLATE_RADIUS = 82.5

    /** Relative image quadrant, not a fixed button coordinate. Round inward on odd rasters. */
    internal fun lowerRightSearchRegion(width: Int, height: Int): Rect {
        require(width >= 8 && height >= 8)
        val left = width / 2 + width % 2
        val top = height / 2 + height % 2
        return Rect(left, top, width - left, height - top)
    }

    internal val SCALES: DoubleArray =
        DoubleArray(SCALE_COUNT) { round6(BASE_SCALE * Math.pow(SCALE_STEP, it.toDouble())) }

    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    private class Entry(
        val scale: Double,
        val tx: Mat,
        val ty: Mat,
        val mask: Mat,
        val energy: Double,
        val maskSum: Double,
    )

    /** Created lazily so merely referencing this object never touches JNI. */
    private val KERNEL3: Mat by lazy { Mat.ones(3, 3, CvType.CV_8U) }
    private val bankLock = Any()

    @Volatile
    private var bankCache: List<Entry>? = null

    /** Bounded immutable template bank. Never contains frame pixels. */
    private fun bank(): List<Entry> {
        val cached = bankCache
        if (cached != null) return cached
        synchronized(bankLock) {
            bankCache?.let { return it }
            val built = buildBank()
            bankCache = built
            return built
        }
    }

    private fun buildBank(): List<Entry> {
        val png = java.util.Base64.getDecoder().decode(AttackCrosshairTemplateData.PNG_BASE64)
        val bytes = MatOfByte(*png)
        val decoded = Mat()
        val base = Mat()
        val entries = ArrayList<Entry>(SCALE_COUNT)
        try {
            Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_GRAYSCALE)?.let { temp ->
                try { temp.copyTo(decoded) } finally { temp.release() }
            }
            require(!decoded.empty()) { "attack crosshair template failed to decode" }
            decoded.convertTo(base, CvType.CV_32F, 1.0 / 255.0)
            for (scale in SCALES) {
                val scaled = Mat()
                val ones = Mat()
                val invert = Mat()
                val blurred = Mat()
                val tx = Mat()
                val ty = Mat()
                val sqX = Mat()
                val sqY = Mat()
                val energy = Mat()
                val edge = Mat()
                val edgeU8 = Mat()
                val dilated = Mat()
                val mask = Mat()
                val maskedX = Mat()
                val maskedY = Mat()
                val maskedSqX = Mat()
                val maskedSqY = Mat()
                val maskedEnergy = Mat()
                var committed = false
                try {
                    Imgproc.resize(
                        base, scaled, Size(), scale, scale,
                        if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR,
                    )
                    ones.create(scaled.rows(), scaled.cols(), CvType.CV_32F)
                    ones.setTo(Scalar(1.0))
                    Core.subtract(ones, scaled, invert)
                    Imgproc.GaussianBlur(invert, blurred, Size(0.0, 0.0), GAUSSIAN_SIGMA)
                    Imgproc.Sobel(blurred, tx, CvType.CV_32F, 1, 0, 3)
                    Imgproc.Sobel(blurred, ty, CvType.CV_32F, 0, 1, 3)
                    Core.multiply(tx, tx, sqX)
                    Core.multiply(ty, ty, sqY)
                    Core.add(sqX, sqY, energy)
                    Imgproc.threshold(energy, edge, EDGE_FLOOR, 1.0, Imgproc.THRESH_BINARY)
                    edge.convertTo(edgeU8, CvType.CV_8U, 255.0)
                    Imgproc.dilate(edgeU8, dilated, KERNEL3)
                    dilated.convertTo(mask, CvType.CV_32F, 1.0 / 255.0)
                    Core.multiply(tx, mask, maskedX)
                    Core.multiply(ty, mask, maskedY)
                    Core.multiply(maskedX, maskedX, maskedSqX)
                    Core.multiply(maskedY, maskedY, maskedSqY)
                    Core.add(maskedSqX, maskedSqY, maskedEnergy)
                    // The bank owns the masked gradients and the support mask; everything else is
                    // a build-time temporary and is released below.
                    entries.add(
                        Entry(
                            scale = scale,
                            tx = maskedX,
                            ty = maskedY,
                            mask = mask,
                            energy = Core.sumElems(maskedEnergy).`val`[0],
                            maskSum = Core.sumElems(mask).`val`[0],
                        ),
                    )
                    committed = true
                } finally {
                    if (!committed) release(mask, maskedX, maskedY)
                    release(scaled, ones, invert, blurred, tx, ty, sqX, sqY, energy, edge, edgeU8,
                        dilated, maskedSqX, maskedSqY, maskedEnergy)
                }
            }
        } catch (failure: Throwable) {
            for (entry in entries) release(entry.tx, entry.ty, entry.mask)
            throw failure
        } finally {
            release(decoded, base, bytes)
        }
        return entries
    }

    /**
     * Recognize the crosshair template in a 3-channel RGB 8U [Mat]. Never throws for malformed
     * input or an OpenCV runtime failure: those yield [TemplateControlStatus.ERROR], which the
     * caller must treat as uncertainty.
     */
    fun detect(image: Mat): TemplateControlResult {
        val start = System.nanoTime()
        return try {
            val budget = budgetNanos
            val first = recognize(image, start, budget)
            // A time exhaustion has just widened the budget: use it on this same frame once, so
            // a slow device does not lose a whole gameplay loop per widening step.
            if (first.status == TemplateControlStatus.EXHAUSTED && budgetNanos > budget &&
                !Thread.currentThread().isInterrupted) {
                val second = recognize(image, System.nanoTime(), budgetNanos)
                second.copy(nanos = System.nanoTime() - start)
            } else first
        } catch (_: Exception) {
            TemplateControlResult(status = TemplateControlStatus.ERROR, nanos = System.nanoTime() - start)
        }
    }

    private fun recognize(image: Mat, start: Long, budget: Long): TemplateControlResult {
        if (image.empty() || image.channels() != 3 || image.depth() != CvType.CV_8U) {
            return TemplateControlResult(status = TemplateControlStatus.ERROR, nanos = System.nanoTime() - start)
        }
        val width = image.cols()
        val height = image.rows()
        if (width < 8 || height < 8) {
            return TemplateControlResult(status = TemplateControlStatus.ERROR, nanos = System.nanoTime() - start)
        }

        val gray = Mat()
        var normalized: Mat? = null
        val normalizedF = Mat()
        val blurred = Mat()
        val gx = Mat()
        val gy = Mat()
        val sqX = Mat()
        val sqY = Mat()
        val ge = Mat()
        var searchX: Mat? = null
        var searchY: Mat? = null
        var searchEnergy: Mat? = null
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
            val norm = min(1.0, min(MAX_FRAME_WIDTH.toDouble() / width, MAX_FRAME_HEIGHT.toDouble() / height))
            val source: Mat
            if (norm < 1.0) {
                val resized = Mat()
                Imgproc.resize(
                    gray, resized,
                    Size(Math.rint(width * norm), Math.rint(height * norm)),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
                normalized = resized
                source = resized
            } else {
                source = gray
            }
            source.convertTo(normalizedF, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(normalizedF, blurred, Size(0.0, 0.0), GAUSSIAN_SIGMA)
            Imgproc.Sobel(blurred, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(blurred, gy, CvType.CV_32F, 0, 1, 3)
            Core.multiply(gx, gx, sqX)
            Core.multiply(gy, gy, sqY)
            Core.add(sqX, sqY, ge)
            val maxGe = Core.minMaxLoc(ge).maxVal

            // Compute derivatives before cropping, preserving the previous gradient boundary
            // semantics. Only the expensive correlations search this user-authorized quadrant.
            val region = lowerRightSearchRegion(gx.cols(), gx.rows())
            val regionX = gx.submat(region)
            searchX = regionX
            val regionY = gy.submat(region)
            searchY = regionY
            val regionEnergy = ge.submat(region)
            searchEnergy = regionEnergy

            val entries = bank()
            val raw = ArrayList<TemplateControlCandidate>(CANDIDATE_CAP)
            var peak = 0f
            var overflow = false
            var timedOut = false

            for (entry in entries) {
                if (Thread.currentThread().isInterrupted || System.nanoTime() - start > budget) { timedOut = true; break }
                if (entry.tx.rows() > regionX.rows() || entry.tx.cols() > regionX.cols()) continue

                val n1 = Mat()
                val n2 = Mat()
                val num = Mat()
                val den = Mat()
                val score = Mat()
                val dilated = Mat()
                val denominator = Mat()
                val energyValid = Mat()
                val absScore = Mat()
                val rangeValid = Mat()
                val valid = Mat()
                val invalid = Mat()
                val aboveThreshold = Mat()
                val localMaximum = Mat()
                val peaks = Mat()
                val locations = Mat()
                try {
                    Imgproc.matchTemplate(regionX, entry.tx, n1, Imgproc.TM_CCORR)
                    Imgproc.matchTemplate(regionY, entry.ty, n2, Imgproc.TM_CCORR)
                    Core.add(n1, n2, num)
                    Imgproc.matchTemplate(regionEnergy, entry.mask, den, Imgproc.TM_CCORR)
                    // Keep dense arithmetic in OpenCV; crossing JNI for every row and doing
                    // millions of scalar JVM square roots exhausted the unchanged400ms budget.
                    val floor = max(1e-6, 8.0 * EPS_F32 * maxGe * entry.maskSum)
                    Core.compare(den, Scalar(floor), energyValid, Core.CMP_GT)
                    Core.multiply(den, Scalar(max(entry.energy, 1e-12)), denominator)
                    Core.max(denominator, Scalar(1e-12), denominator)
                    Core.sqrt(denominator, denominator)
                    Core.divide(num, denominator, score)
                    Core.patchNaNs(score, -1.0)
                    Core.absdiff(score, Scalar(0.0), absScore)
                    Core.compare(absScore, Scalar(SCORE_SLACK.toDouble()), rangeValid, Core.CMP_LE)
                    Core.bitwise_and(energyValid, rangeValid, valid)
                    Core.bitwise_not(valid, invalid)
                    score.setTo(Scalar(-1.0), invalid)
                    Core.max(score, Scalar(-1.0), score)
                    Core.min(score, Scalar(1.0), score)
                    val extremum = Core.minMaxLoc(score)
                    if (extremum.maxVal.isFinite() && extremum.maxVal.toFloat() > peak) {
                        peak = extremum.maxVal.toFloat()
                    }
                    Imgproc.dilate(score, dilated, KERNEL3)
                    Core.compare(score, Scalar(THRESHOLD.toDouble()), aboveThreshold, Core.CMP_GE)
                    Core.compare(score, dilated, localMaximum, Core.CMP_GE)
                    Core.bitwise_and(aboveThreshold, localMaximum, peaks)
                    Core.findNonZero(peaks, locations)
                    val count = locations.rows()
                    if (raw.size.toLong() + count.toLong() > RAW_PEAK_CAP.toLong()) {
                        overflow = true
                        break
                    }
                    if (count > 0) {
                        val coordinates = IntArray(count * 2)
                        locations.get(0, 0, coordinates)
                        val value = FloatArray(1)
                        for (index in 0 until count) {
                            val col = coordinates[index * 2]
                            val row = coordinates[index * 2 + 1]
                            score.get(row, col, value)
                            raw.add(TemplateControlCandidate(
                                x = ((region.x + col + TEMPLATE_CENTER_X * entry.scale) / norm).toFloat(),
                                y = ((region.y + row + TEMPLATE_CENTER_Y * entry.scale) / norm).toFloat(),
                                radius = (TEMPLATE_RADIUS * entry.scale / norm).toFloat(),
                                score = value[0],
                            ))
                        }
                    }
                } finally {
                    release(n1, n2, num, den, score, dilated, denominator, energyValid, absScore,
                        rangeValid, valid, invalid, aboveThreshold, localMaximum, peaks, locations)
                }
            }

            val nanos = System.nanoTime() - start
            if (timedOut || overflow || Thread.currentThread().isInterrupted || nanos > budget) {
                // Only a genuine time exhaustion widens the next attempt; an interrupt (STOP)
                // or a candidate overflow says nothing about this device's speed.
                if (!overflow && !Thread.currentThread().isInterrupted) budgetNanos = escalatedBudget(budget)
                return TemplateControlResult(
                    status = TemplateControlStatus.EXHAUSTED,
                    candidates = raw.size,
                    peakScore = peak,
                    nanos = nanos,
                )
            }
            raw.sortWith(compareByDescending { it.score })
            val kept = ArrayList<TemplateControlCandidate>(min(raw.size, CANDIDATE_CAP))
            for (candidate in raw) {
                if (!candidate.usable) continue
                if (kept.any { samePeak(it, candidate) }) continue
                kept.add(candidate)
                if (kept.size > CANDIDATE_CAP) {
                    return TemplateControlResult(
                        status = TemplateControlStatus.EXHAUSTED,
                        candidates = kept.size,
                        peakScore = peak,
                        nanos = nanos,
                    )
                }
            }
            val completeNanos = System.nanoTime() - start
            if (Thread.currentThread().isInterrupted || completeNanos > budget) {
                if (!Thread.currentThread().isInterrupted) budgetNanos = escalatedBudget(budget)
                return TemplateControlResult(status = TemplateControlStatus.EXHAUSTED,
                    candidates = kept.size, peakScore = peak, nanos = completeNanos)
            }
            val status = when {
                kept.isEmpty() -> TemplateControlStatus.NONE
                kept.size == 1 -> TemplateControlStatus.SINGLE
                else -> TemplateControlStatus.AMBIGUOUS
            }
            return TemplateControlResult(
                status = status,
                candidate = if (status == TemplateControlStatus.SINGLE) kept[0] else null,
                candidates = kept.size,
                peakScore = peak,
                nanos = completeNanos,
            )
        } finally {
            searchX?.let { release(it) }
            searchY?.let { release(it) }
            searchEnergy?.let { release(it) }
            release(gray, normalizedF, blurred, gx, gy, sqX, sqY, ge)
            normalized?.let { release(it) }
        }
    }

    /** Prototype de-duplication: near in position AND compatible in estimated radius. */
    private fun samePeak(a: TemplateControlCandidate, b: TemplateControlCandidate): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= max(2.0, min(a.radius, b.radius) * 0.20) &&
            max(a.radius, b.radius) <= min(a.radius, b.radius) * 1.25f

    private fun release(vararg mats: Mat) {
        for (mat in mats) {
            try {
                mat.release()
            } catch (_: Exception) {
                // A release failure must not change the recognition decision.
            }
        }
    }
}
