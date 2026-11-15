package com.pyla.ai.engine

import android.os.SystemClock
import com.pyla.ai.PylaApp
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Observation only. Never grants session/input authority or persists user configuration. */
object PhoneVisualCues {
    data class SuperPoint(val x: Float, val y: Float)
    private val templates = HashMap<String, Mat>()
    private val chaosGate = ChaosContinuationGate()
    private fun size(image: Mat) = (image.cols().toLong() shl 32) or image.rows().toLong()

    /**
     * Lifecycle generation to capture BEFORE a frame whose observation may arm the coins
     * continuation. It is threaded through the classifier so an observation that straddled a
     * Pause/Stop/session replacement can neither arm the continuation nor continue the dismissal.
     */
    fun chaosObservationGeneration(): Long = chaosGate.observationGeneration()

    @Synchronized fun revokeChaosContinuation(expectedGeneration: Long? = null) {
        chaosGate.invalidate(SystemClock.elapsedRealtime(), expectedGeneration)
    }

    private fun template(name: String, w: Int, h: Int): Mat? {
        val unit = com.pyla.ai.capture.UiLayout.unit(w, h, 720.0)
        val key = "$name:$w:$h"
        templates[key]?.let { return it }
        return try {
            val bytes = PylaApp.ctx().assets.open("pyla/images/phone_feedback_cues/$name.png").use { it.readBytes() }
            val encoded = MatOfByte(*bytes)
            val raw = try { Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE) } finally { encoded.release() }
            if (raw.empty()) { raw.release(); null } else {
                val scaled = Mat()
                try { Imgproc.resize(raw, scaled, Size((raw.cols() * unit).coerceAtLeast(1.0), (raw.rows() * unit).coerceAtLeast(1.0))) }
                finally { raw.release() }
                templates[key] = scaled; scaled
            }
        } catch (_: Exception) { null }
    }
    private data class Match(val score: Double, val x: Double, val y: Double)
    private fun match(gray: Mat, name: String, x0: Double, y0: Double, x1: Double, y1: Double): Match? {
        val scale = com.pyla.ai.capture.UiLayout.unit(gray.cols(), gray.rows(), 720.0)
        val left = (x0 * scale).toInt().coerceIn(0, gray.cols())
        val right = (x1 * scale).toInt().coerceIn(0, gray.cols())
        val topEdge = com.pyla.ai.capture.UiLayout.top(y0, y1 - y0, gray.cols(), gray.rows(), 720.0)
        val top = topEdge.toInt().coerceIn(0, gray.rows())
        val bottom = (topEdge + (y1 - y0) * scale).toInt().coerceIn(0, gray.rows())
        return match(gray, name, SuperReadinessPolicy.Bounds(left, top, right, bottom))
    }
    private fun match(gray: Mat, name: String, bounds: SuperReadinessPolicy.Bounds): Match? {
        val t = template(name, gray.cols(), gray.rows()) ?: return null
        val (left, top, right, bottom) = bounds
        if (right-left < t.cols() || bottom-top < t.rows()) return null
        val crop = Mat(gray, Rect(left,top,right-left,bottom-top)); val scores = Mat()
        return try {
            Imgproc.matchTemplate(crop,t,scores,Imgproc.TM_CCOEFF_NORMED)
            val m = Core.minMaxLoc(scores)
            if (!m.maxVal.isFinite()) null else Match(m.maxVal,left+m.maxLoc.x+t.cols()/2.0,top+m.maxLoc.y+t.rows()/2.0)
        } finally { scores.release(); crop.release() }
    }
    @Synchronized fun superReady(image: Mat, policy: SuperReadinessPolicy? = SuperReadinessPolicy.defaults()): SuperPoint? {
        if (image.empty() || image.channels()!=3) return null
        val bounds = policy?.searchBounds(image.cols(), image.rows()) ?: return null
        val gray=Mat(); Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
        val s=com.pyla.ai.capture.UiLayout.unit(image.cols(), image.rows(), 720.0)
        val m=try {
            listOf("super_ready_skull", "super_ready_skull_active", "super_ready_skull_pulse")
                .mapNotNull { match(gray,it,bounds) }.maxByOrNull { it.score }
        } finally { gray.release() }
        if (m==null || m.score<=0.90) return null
        val radius=44.0*s;val x0=(m.x-radius).toInt();val y0=(m.y-radius).toInt();val x1=(m.x+radius).toInt();val y1=(m.y+radius).toInt()
        if (x0<0 || y0<0 || x1>image.cols() || y1>image.rows() || x1<=x0 || y1<=y0) return null
        val roi=Mat(image,Rect(x0,y0,x1-x0,y1-y0));val hsv=Mat();val mask=Mat()
        return try {
            Imgproc.cvtColor(roi,hsv,Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv,Scalar(17.0,170.0,200.0),Scalar(34.0,255.0,255.0),mask)
            if (policy.acceptsYellow(Core.countNonZero(mask).toLong(), mask.total())) SuperPoint(m.x.toFloat(),m.y.toFloat()) else null
        } finally { mask.release();hsv.release();roi.release() }
    }
    /** Coins dismissal is admitted only immediately after positively observed Chaosdrop artwork. */
    @Synchronized fun isChaosDrop(
        image: Mat,
        nowMs: Long = SystemClock.elapsedRealtime(),
        chaosGeneration: Long? = chaosGate.observationGeneration(),
        capturedAtMs: Long = nowMs,
    ): Boolean {
        if (image.empty() || image.channels()!=3) return false
        val gray=Mat();Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
        val s=com.pyla.ai.capture.UiLayout.unit(image.cols(), image.rows(), 720.0);val center=image.cols()/s/2.0
        return try {
            val header=match(gray,"chaos_daily_wins",0.0,0.0,600.0,140.0)?.score ?: 0.0
            val face=if(header>0.90) listOf("rare","super_rare","epic").maxOf { match(gray,"chaos_face_$it",center-280.0,220.0,center+300.0,600.0)?.score ?: 0.0 } else 0.0
            // Unbound read-only classifiers may identify artwork, but cannot create a chain.
            if(header>0.90 && face>0.86) chaosGeneration == null || chaosGate.arm(chaosGeneration, nowMs, size(image), capturedAtMs)
            else if(chaosGeneration != null && chaosGate.continues(chaosGeneration, nowMs, size(image), capturedAtMs)) {
                val title=match(gray,"chaos_reward_coins_title",center-330.0,30.0,center+340.0,210.0)?.score ?: 0.0
                val coin=match(gray,"chaos_reward_coin_icon",center-260.0,330.0,center+160.0,600.0)?.score ?: 0.0
                (title>0.90 && coin>0.90) || isRewardReveal(image)
            } else false
        } finally { gray.release() }
    }

    /**
     * Generic reward-reveal screen: one saturated hue fills the whole outer frame band.
     *
     * The coins templates above only recognise one of many rewards; a Starr Drop that revealed
     * Power Points left the bot on the reward screen for good. Every reveal shares the plain
     * single-hue radial background, measured at 0.995 of the outer band on a Power Points reveal,
     * against at most 0.71 on lobby, match, result, elimination and dialog frames and 0.87 on the
     * drop artwork itself. Only consulted inside the armed continuation, i.e. within seconds of
     * positively observed drop artwork, so it can never classify an arbitrary screen.
     */
    internal fun isRewardReveal(image: Mat): Boolean {
        val w = image.cols(); val h = image.rows()
        val band = (h * 0.12).toInt()
        if (band < 4 || w <= 2 * band || h <= 2 * band) return false
        val strips = listOf(
            Rect(0, 0, w, band), Rect(0, h - band, w, band),
            Rect(0, band, band, h - 2 * band), Rect(w - band, band, band, h - 2 * band),
        )
        val hsvStrips = ArrayList<Mat>(4)
        return try {
            val histogram = LongArray(180)
            var total = 0L
            for (strip in strips) {
                // Every 4th pixel in both directions is ample for a flat background and keeps
                // this check, which runs on every unclassified frame, cheap.
                val roi = Mat(image, strip); val small = Mat(); val hsv = Mat()
                try {
                    Imgproc.resize(roi, small, Size((strip.width / 4).coerceAtLeast(1).toDouble(),
                        (strip.height / 4).coerceAtLeast(1).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
                    Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
                } finally { roi.release(); small.release() }
                hsvStrips += hsv
                val bytes = ByteArray(hsv.rows() * hsv.cols() * 3)
                hsv.get(0, 0, bytes)
                var i = 0
                while (i < bytes.size) { histogram[(bytes[i].toInt() and 0xFF).coerceAtMost(179)]++; i += 3 }
                total += bytes.size / 3
            }
            if (total == 0L) return false
            var seen = 0L; var median = 0
            while (median < 179 && seen + histogram[median] < (total + 1) / 2) { seen += histogram[median]; median++ }
            var matching = 0L
            for (hsv in hsvStrips) {
                val bytes = ByteArray(hsv.rows() * hsv.cols() * 3)
                hsv.get(0, 0, bytes)
                var i = 0
                while (i < bytes.size) {
                    val hue = bytes[i].toInt() and 0xFF
                    val distance = kotlin.math.abs(hue - median).let { minOf(it, 180 - it) }
                    if (distance <= 10 && (bytes[i + 1].toInt() and 0xFF) >= 100 && (bytes[i + 2].toInt() and 0xFF) >= 60) matching++
                    i += 3
                }
            }
            matching.toDouble() / total >= REWARD_REVEAL_BAND_FRACTION
        } catch (_: Exception) { false } finally { hsvStrips.forEach { it.release() } }
    }

    private const val REWARD_REVEAL_BAND_FRACTION = 0.95
}
