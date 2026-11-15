package com.pyla.ai.engine

/**
 * Pure, OpenCV-free red attack-control recognizer. This is the production decision; the
 * [AttackButtonDetector] `Mat` bridge only marshals pixels into it.
 *
 * Input is an interleaved 3-bytes-per-pixel RGB buffer (`rgb[3*(y*width+x) + {0,1,2}] = R,G,B`),
 * row-major, matching `Mat.get(0,0,ByteArray)` on a continuous 3-channel Mat.
 *
 * ### Measured constraints (see COLOR-ANALYSIS.md)
 * Thresholds derive from the real user gameplay frames, not from an invented hex color:
 *  - The control's red ring samples (video-025) sit at R[122..191] G[0..42] B[0..50],
 *    HSV H~[170..180]u[0..10], S>=160, V>=90. The rule below is the equivalent RGB form.
 *  - The orange/red world walls behind the HUD sample R~[180..252] G~[62..164]: they are
 *    excluded by requiring low green and blue relative to red.
 *  - Enemy health bars are thin elongated red rectangles and are excluded by aspect ratio.
 *  - Solid red boxes/blobs are excluded by the enclosed non-red core and the fill-ratio range.
 *
 * Every bound is a fraction of the capture height, so the same control is recognized after
 * being repositioned or rescaled.
 */
object AttackButtonPixels {

    // --- red ring color rule (RGB equivalent of the measured HSV band) ---
    private const val MIN_R = 120
    private const val MAX_G = 45
    private const val MAX_B = 55
    private const val MIN_R_MINUS_G = 60
    private const val MIN_R_MINUS_B = 55
    private const val MIN_V = 90 // reject near-black reds (shadowed map pixels)

    // --- shape rule, relative to capture height ---
    private const val MIN_SIDE_FRACTION = 0.055  // support a half-size custom HUD control
    private const val MAX_SIDE_FRACTION = 0.34   // 720 -> 244.8 px ; above this it is a wall/panel
    private const val MAX_ASPECT = 1.40          // reject elongated world/enemy bars
    private const val MIN_FILL = 0.42            // ring occupies its box (not a sparse scatter)
    private const val MAX_FILL = 0.94            // reject filled boxes (a ring/round disc is < 0.94)
    private const val MIN_AREA = 500             // ignore specks
    private const val CORE_RADIUS_FRACTION = 0.34 // non-red hole radius relative to outer radius
    private const val MIN_CORE_NON_RED = 0.25     // the enclosed core must be substantially non-red

    /**
     * Upper bound on the pixel area whose scratch buffers are retained per thread. Frames above
     * this are still processed exactly, but with throwaway buffers, so a pathological capture can
     * never pin an unbounded amount of memory.
     */
    internal const val MAX_RETAINED_PIXELS = 2_100_000

    private val EMPTY_BOOLEAN = BooleanArray(0)
    private val EMPTY_INT = IntArray(0)

    /**
     * Thread-confined, bounded scratch for the per-frame pixel masks. Every element that a call
     * reads is written by that same call before the read (the red mask is assigned for every
     * index, the label plane is cleared for the used span, the stack is only read below its
     * current top), so no mask content can leak between callers or frames. The buffers are
     * private to one thread and reallocated only when the frame area grows.
     */
    internal class Scratch {
        var pixels: Int = 0
            private set
        var red: BooleanArray = EMPTY_BOOLEAN
            private set
        var labels: IntArray = EMPTY_INT
            private set
        var stack: IntArray = EMPTY_INT
            private set

        /** Retained-buffer allocations, for deterministic host tests (never used by the algorithm). */
        var allocations: Int = 0
            private set
        /** Throwaway allocations for frames above [MAX_RETAINED_PIXELS]. */
        var transientAllocations: Int = 0
            private set

        internal fun resize(pixels: Int) {
            this.pixels = pixels
            red = BooleanArray(pixels)
            labels = IntArray(pixels)
            stack = IntArray(pixels)
        }

        internal fun countRetainedAllocation() { allocations++ }

        internal fun countTransientAllocation() { transientAllocations++ }
    }

    private val scratchHolder = ThreadLocal<Scratch?>()

    /**
     * Returns a thread-confined scratch sized for at least [pixels]. Only the [Scratch] that is
     * actually retained is grown; oversized frames get a fresh, unretained one.
     */
    internal fun scratchFor(pixels: Int): Scratch {
        var scratch = scratchHolder.get()
        if (scratch == null) {
            scratch = Scratch()
            scratchHolder.set(scratch)
        }
        if (scratch.pixels >= pixels) return scratch
        if (pixels > MAX_RETAINED_PIXELS) {
            scratch.countTransientAllocation()
            val transient = Scratch()
            transient.resize(pixels)
            return transient
        }
        scratch.resize(pixels)
        scratch.countRetainedAllocation()
        return scratch
    }

    /** Test-only view of the retaining scratch of the calling thread. */
    internal fun retainedScratchForTest(): Scratch = scratchFor(0)

    /**
     * Recognize the control. Returns `null` for a missing, malformed, or ambiguous observation
     * (zero candidates, or more than one equally plausible control).
     */
    fun detect(width: Int, height: Int, rgb: ByteArray): AttackButtonPoint? = detect(width, height, rgb, null)

    /**
     * Same decision as [detect], additionally reporting bounded phase timings into [metrics].
     * The collector is an output sink only: it cannot change the decision. Opaque and translucent
     * evidence are merged across the complete frame; neither path may hide ambiguity in the other.
     */
    internal fun detect(
        width: Int,
        height: Int,
        rgb: ByteArray,
        metrics: AttackRecognitionMetrics?,
        controlQuadrantOnly: Boolean = false,
    ): AttackButtonPoint? {
        if (width < 8 || height < 8) { metrics?.outcome = AttackRecognitionOutcome.INVALID_FRAME; return null }
        val pixels = width.toLong() * height.toLong()
        if (pixels > 16_777_216L || rgb.size.toLong() < pixels * 3L) {
            metrics?.outcome = AttackRecognitionOutcome.INVALID_FRAME
            return null
        }

        val scratch = scratchFor(pixels.toInt())
        val opaqueStart = System.nanoTime()
        val opaque = detectOpaque(width, height, rgb, scratch)
        metrics?.opaqueNanos = System.nanoTime() - opaqueStart
        if (opaque.ambiguous) { metrics?.outcome = AttackRecognitionOutcome.OPAQUE_AMBIGUOUS; return null }
        val translucentStart = System.nanoTime()
        val found = TranslucentAttackControl.find(width, height, rgb, scratch, metrics)
        // Calibration only: the attack control lives in the lower-right quadrant (the same region
        // the template search is confined to). Warm ring-shaped world objects elsewhere - glowing
        // crates were measured as two "controls" in the upper half of a 16:9 frame - are not
        // competing controls and must not veto or contradict the real one.
        val translucent = if (!controlQuadrantOnly) found else found.copy(
            points = found.points.filter { it.x >= width / 2f && it.y >= height / 2f })
        metrics?.translucentNanos = System.nanoTime() - translucentStart
        if (translucent.exhausted || translucent.points.size > 1) {
            metrics?.outcome = if (translucent.exhausted) AttackRecognitionOutcome.TRANSLUCENT_EXHAUSTED
                else AttackRecognitionOutcome.TRANSLUCENT_AMBIGUOUS
            return null
        }
        val resting = translucent.points.singleOrNull()
        val pressed = opaque.point
        if (pressed != null && resting != null && !TranslucentAttackControl.sameControl(pressed, resting)) {
            metrics?.outcome = AttackRecognitionOutcome.CROSS_PATH_CONFLICT
            return null
        }
        metrics?.outcome = when {
            pressed != null && resting != null -> AttackRecognitionOutcome.BOTH_SAME_CONTROL
            pressed != null -> AttackRecognitionOutcome.OPAQUE_POINT
            resting != null -> AttackRecognitionOutcome.TRANSLUCENT_POINT
            else -> AttackRecognitionOutcome.NOT_FOUND
        }
        return pressed ?: resting
    }

    private data class OpaqueResult(val point: AttackButtonPoint?, val ambiguous: Boolean = false)

    /** Shared exact opaque category: extracting this predicate does not relax its mask. */
    internal fun isOpaqueRed(r: Int, g: Int, b: Int): Boolean =
        r >= MIN_R && g <= MAX_G && b <= MAX_B &&
            (r - g) >= MIN_R_MINUS_G && (r - b) >= MIN_R_MINUS_B && max3(r, g, b) >= MIN_V

    /** The original opaque thresholds and component/shape guards are unchanged. */
    private fun detectOpaque(width: Int, height: Int, rgb: ByteArray, scratch: Scratch): OpaqueResult {
        // Reused per-thread buffers: [red] is assigned for every index below, [labels] is cleared
        // over exactly the span this call can touch, so stale content can never be observed.
        val red = scratch.red
        val labels = scratch.labels
        val stack = scratch.stack
        val pixelCount = width * height
        java.util.Arrays.fill(labels, 0, pixelCount, 0)
        var i = 0
        var p = 0
        while (p < pixelCount) {
            val r = rgb[i].toInt() and 0xFF
            val g = rgb[i + 1].toInt() and 0xFF
            val b = rgb[i + 2].toInt() and 0xFF
            red[p] = isOpaqueRed(r, g, b)
            i += 3
            p++
        }

        val bbox = IntArray(4) // reused scratch: x0,y0,x1,y1
        val candidates = ArrayList<AttackButtonPoint>(2)

        var nextLabel = 0
        for (start in 0 until pixelCount) {
            if (!red[start] || labels[start] != 0) continue
            nextLabel += 1
            val label = nextLabel
            var sp = 0
            stack[sp++] = start
            labels[start] = label
            var x0 = width
            var y0 = height
            var x1 = -1
            var y1 = -1
            var area = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % width
                val cy = cur / width
                area++
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                // 4-connectivity, matching the reference measurement tooling.
                if (cx > 0) { val n = cur - 1; if (red[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cx < width - 1) { val n = cur + 1; if (red[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cy > 0) { val n = cur - width; if (red[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cy < height - 1) { val n = cur + width; if (red[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
            }

            bbox[0] = x0; bbox[1] = y0; bbox[2] = x1; bbox[3] = y1
            evaluateCandidate(width, height, red, area, bbox, labels, label)?.let {
                candidates.add(it)
                if (candidates.size > 1) return OpaqueResult(null, ambiguous = true)
            }
        }

        return OpaqueResult(candidates.singleOrNull())
    }

    /** Apply the shape/hole constraints to one red component. Returns its center or null. */
    private fun evaluateCandidate(
        width: Int,
        height: Int,
        red: BooleanArray,
        area: Int,
        bbox: IntArray,
        labels: IntArray,
        label: Int,
    ): AttackButtonPoint? {
        if (area < MIN_AREA) return null
        val w = bbox[2] - bbox[0] + 1
        val h = bbox[3] - bbox[1] + 1
        val longSide = maxOf(w, h)
        val shortSide = minOf(w, h)
        if (shortSide.toDouble() / height < MIN_SIDE_FRACTION) return null
        if (longSide.toDouble() / height > MAX_SIDE_FRACTION) return null
        if (longSide.toDouble() / shortSide > MAX_ASPECT) return null
        val fill = area.toDouble() / (w.toDouble() * h.toDouble())
        if (fill < MIN_FILL || fill > MAX_FILL) return null

        val roughX = (bbox[0] + bbox[2]) / 2.0
        val roughY = (bbox[1] + bbox[3]) / 2.0
        val roughRadius = shortSide / 2.0

        // A hollow red rectangle also has a pale core: reject its red square corners.
        var outsideEllipse = 0
        for (y in bbox[1]..bbox[3]) for (x in bbox[0]..bbox[2]) {
            val nx = (x - roughX) / (w / 2.0)
            val ny = (y - roughY) / (h / 2.0)
            if (nx * nx + ny * ny > 1.10 && red[y * width + x]) outsideEllipse++
        }
        if (outsideEllipse.toDouble() / area > 0.06) return null

        val fitted = AttackButtonCircleFit.fit(red, width, height, bbox, roughX, roughY, roughRadius, labels, label) ?: return null
        val cx = fitted.x.toDouble()
        val cy = fitted.y.toDouble()
        val radius = fitted.radius.toDouble()

        // The exact center must be enclosed by the ring, i.e. itself non-red (icon/touch hole).
        val centerIdx = (cy.toInt() * width + cx.toInt())
        if (centerIdx < 0 || centerIdx >= red.size || red[centerIdx]) return null

        // A meaningful non-red core must sit inside the ring, so a solid red box cannot pass.
        val coreR = radius * CORE_RADIUS_FRACTION
        var coreTotal = 0
        var coreNonRed = 0
        val yStart = maxOf(bbox[1], (cy - coreR).toInt())
        val yEnd = minOf(bbox[3], (cy + coreR).toInt())
        val xStart = maxOf(bbox[0], (cx - coreR).toInt())
        val xEnd = minOf(bbox[2], (cx + coreR).toInt())
        var yy = yStart
        while (yy <= yEnd) {
            var xx = xStart
            while (xx <= xEnd) {
                val dx = xx - cx
                val dy = yy - cy
                if (dx * dx + dy * dy <= coreR * coreR) {
                    coreTotal++
                    if (!red[yy * width + xx]) coreNonRed++
                }
                xx++
            }
            yy++
        }
        if (coreTotal == 0) return null
        if (coreNonRed.toDouble() / coreTotal < MIN_CORE_NON_RED) return null

        return AttackButtonPoint(cx.toFloat(), cy.toFloat(), radius.toFloat())
    }

    private fun max3(a: Int, b: Int, c: Int): Int = if (a >= b && a >= c) a else if (b >= c) b else c
}
