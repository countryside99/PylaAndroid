package com.pyla.ai.engine

/**
 * A control location in capture-pixel coordinates: either a current visual observation or an
 * explicitly learned, geometry-bound calibration. The value itself grants no input authority;
 * callers still enforce fresh gameplay, STOP, geometry and guarded dispatch independently.
 */
data class AttackButtonPoint(val x: Float, val y: Float, val radius: Float)

/**
 * Production pixel-geometry recognizer for the circular red attack control.
 *
 * The recognition rule is pure and OpenCV-free so it can be exercised on real capture frames in
 * plain JVM tests through [AttackButtonPixels] (the [AttackButtonDetector] bridge is the only
 * part that touches a `Mat`). This keeps the tested decision byte-for-byte identical to the
 * decision the product runs.
 *
 * The control is identified by structure, never by a remembered screen position:
 *  - it is a compact, roughly circular ring of strongly saturated pure red,
 *  - the ring encloses a non-red core (the weapon icon and/or the pale touch indicator), so the
 *    exact center pixel is intentionally *not* required to be red,
 *  - it is neither an elongated red world bar (enemy health bars) nor a solid red rectangle
 *    (plain red boxes / ambiguous panels) nor a solid red blob with a red center.
 *
 * Any frame yielding zero or more than one such control is treated as uncertainty and produces
 * `null`; there is deliberately no fixed-coordinate fallback. The bounds are expressed as
 * fractions of the capture height, so a rescaled or repositioned control is still recognized.
 */
object AttackButtonDetector {

    /**
     * Latest trace produced by a completed recognition on THIS thread, or `null` when the last
     * call produced no completed analysis (empty/wrong-format/oversized frame, failed pixel
     * read, or a throwing `Mat`). The value is immutable and is replaced only within the
     * recognizing call, so a reader on the same thread always sees either the newest completed
     * trace or `null`, never a stale one from an earlier frame.
     */
    private val latestTrace = ThreadLocal<AttackRecognitionTrace?>()

    /** The latest completed trace of this thread, or `null`. Grants no input authority. */
    fun lastTrace(): AttackRecognitionTrace? = latestTrace.get()

    /**
     * Recognize the attack control in a 3-channel RGB [org.opencv.core.Mat] whose pixel data is
     * continuous `RGBRGB...`. Coordinates in the returned point are capture pixels.
     *
     * Returns `null` when the image is empty, not 3-channel, or the control is missing or
     * ambiguous.
     */
    fun detect(image: org.opencv.core.Mat): AttackButtonPoint? = detect(image, false)

    private fun detect(image: org.opencv.core.Mat, controlQuadrantOnly: Boolean): AttackButtonPoint? {
        // Stale evidence is cleared before ANY outcome of this call, including an invalid Mat or
        // a throwing one, so a failed frame can never be attributed to the previous frame's trace.
        latestTrace.set(null)
        if (image.empty() || image.channels() != 3 || image.depth() != org.opencv.core.CvType.CV_8U) return null
        val width = image.cols()
        val height = image.rows()
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_777_216L) return null
        val bytes = ByteArray(width * height * 3)
        try {
            if (image.get(0, 0, bytes) != bytes.size) return null
        } catch (_: Exception) {
            return null
        }
        // Mat.get on a continuous 3-channel Mat yields row-major interleaved RGB bytes, which is
        // exactly the layout AttackButtonPixels expects.
        val metrics = AttackRecognitionMetrics()
        val point = AttackButtonPixels.detect(width, height, bytes, metrics, controlQuadrantOnly)
        // Only a completed analysis of this very call is published, and only after it finished,
        // so a reader can never observe a partially filled trace.
        latestTrace.set(metrics.toTrace())
        return point
    }

    /**
     * Attack-control recognition: the unchanged two-path pixel decision of [detect] plus the
     * crosshair-template fallback. This is the ONLY entry point that runs the template bank.
     *
     * [detect] deliberately keeps its exact old semantics (no template) because
     * `StateFinder` also calls it to decide whether a menu is gameplay; template evidence must
     * never acquire screen-state authority through that boundary. Only the attack caller
     * (Play.kt `locateAttackButton`) uses this method.
     *
     * Returns `null` for every old invalid/failed frame BEFORE any template work, and `null` for
     * any baseline ambiguity, template ambiguity/exhaustion/error, or conflicting geometry. The
     * published trace carries the merged outcome plus the template phase counters.
     */
    fun detectForAttack(image: org.opencv.core.Mat): AttackButtonPoint? =
        detectForAttack(image) { TemplateAttackControl.detect(it) }

    /**
     * Learning requires a SINGLE template-supported control, never a legacy-only point.
     * Reuse the existing ambiguity/conflict/budget checks before accepting either observation.
     * This entry point is not used by StateFinder and grants no gameplay/input authority.
     */
    fun detectForCalibration(image: org.opencv.core.Mat): AttackButtonPoint? =
        detectForCalibration(image) { TemplateAttackControl.detect(it) }

    internal fun detectForCalibration(
        image: org.opencv.core.Mat,
        templateMatcher: (org.opencv.core.Mat) -> TemplateControlResult,
    ): AttackButtonPoint? {
        var template: TemplateControlResult? = null
        val merged = detectForAttack(image, controlQuadrantOnly = true) { templateMatcher(it).also { result -> template = result } }
        if (merged == null) return null
        when (latestTrace.get()?.outcome) {
            AttackRecognitionOutcome.TEMPLATE_POINT,
            AttackRecognitionOutcome.BOTH_TEMPLATE_SAME_CONTROL -> Unit
            else -> return null
        }
        // Store the actual template center, not the agreeing legacy detector's fitted center.
        val candidate = template?.takeIf { it.status == TemplateControlStatus.SINGLE }?.candidate ?: return null
        return candidate.takeIf { it.usable }?.let { AttackButtonPoint(it.x, it.y, it.radius) }
    }

    /** No matching occurred: deliberately do not attribute an old trace to a cached location. */
    fun recordSavedCalibration() {
        latestTrace.set(AttackRecognitionTrace(
            opaqueNanos = 0, translucentNanos = 0, coarseNanos = 0, refineNanos = 0,
            coarseCandidates = 0, refinementSeeds = 0, exhausted = false,
            outcome = AttackRecognitionOutcome.SAVED_CALIBRATION_POINT,
        ))
    }

    /**
     * Test seam: identical bridge and merge, with the template recognizer injected so host tests
     * need no JNI. It is not a runtime fallback: production always passes the real recognizer.
     */
    internal fun detectForAttack(
        image: org.opencv.core.Mat,
        controlQuadrantOnly: Boolean = false,
        templateMatcher: (org.opencv.core.Mat) -> TemplateControlResult,
    ): AttackButtonPoint? {
        latestTrace.set(null)
        val baselinePoint = detect(image, controlQuadrantOnly)
        val baseline = latestTrace.get() ?: return null
        // Baseline ambiguity or a malformed frame vetoes before any template work.
        if (TemplateAttackControlMerge.vetoesTemplate(baseline.outcome)) return null
        val templateStarted = System.nanoTime()
        val template = try {
            templateMatcher(image)
        } catch (_: Exception) {
            TemplateControlResult(
                status = TemplateControlStatus.ERROR,
                nanos = System.nanoTime() - templateStarted,
            )
        }
        val decision = TemplateAttackControlMerge.decide(baseline.outcome, baselinePoint, template)
        latestTrace.set(
            AttackRecognitionTrace(
                opaqueNanos = baseline.opaqueNanos,
                translucentNanos = baseline.translucentNanos,
                coarseNanos = baseline.coarseNanos,
                refineNanos = baseline.refineNanos,
                coarseCandidates = baseline.coarseCandidates,
                refinementSeeds = baseline.refinementSeeds,
                exhausted = baseline.exhausted,
                outcome = decision.outcome,
                templateNanos = template.nanos,
                templateCandidates = template.candidates,
                templatePeakScore = template.peakScore,
            ),
        )
        return decision.point
    }
}
