package com.pyla.ai.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.engine.BotStatus
import com.pyla.ai.engine.PylaLog

class InputService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private class Channel(val name: String) {
        var lastStroke: GestureDescription.StrokeDescription? = null
        var curX = 0f; var curY = 0f
        var downX = 0f; var downY = 0f
        var targetX = 0f; var targetY = 0f
        var wantDown = false
        var isDown = false

        // A continuable accessibility stroke must be followed by another continuation or Android
        // eventually lifts the pointer. Keep pumping while held, independently of whether the bot's
        // logical target changed. The logical re_apply_movement setting is handled by
        // WindowController, just like the PC version.
        fun needsUpdate(): Boolean = wantDown != isDown || (isDown && wantDown)
        fun reset() { lastStroke = null; isDown = false }
    }

    private val joystickCh = Channel("joystick")
    private val attackCh = Channel("attack")
    private class Pending(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long)
    private val strokeQueue = ArrayDeque<Pending>()
    private var gestureInFlight = false
    private var pressSeq = 0
    private var lastQueueWarningMs = 0L

    override fun onServiceConnected() {
        instance = this
        BotStatus.inputConnected = true
        PylaLog.p(TAG, "InputService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        BotStatus.inputConnected = false
        handler.post {
            joystickCh.reset(); joystickCh.wantDown = false
            attackCh.reset(); attackCh.wantDown = false
            strokeQueue.clear()
            gestureInFlight = false
        }
        PylaLog.w(TAG, "InputService unbound")
        return super.onUnbind(intent)
    }

    private val launchableCache = HashMap<String, Boolean>()

    private fun isLaunchableApp(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) {
            try {
                packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (t: Throwable) {
                false
            }
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg.isEmpty() || pkg == "com.android.systemui") return
            if (isLaunchableApp(pkg)) {
                if (foregroundPkg != pkg) PylaLog.p(TAG, "foreground app: $pkg")
                foregroundPkg = pkg
            }
        }
    }

    override fun onInterrupt() {}

    fun tap(key: String, holdMs: Long = 40) {
        val (x, y) = InputCoordinates.press(key)
        if (x < 0) return
        tapAt(x.toFloat(), y.toFloat(), holdMs)
    }

    fun tapAt(x: Float, y: Float, holdMs: Long = 40) {
        handler.post {
            enqueueStroke(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000)))
            pump()
        }
    }

    fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        handler.post {
            enqueueStroke(Pending(x1, y1, x2, y2, durationMs.coerceIn(1, 10_000)))
            pump()
        }
    }

    private fun enqueueStroke(pending: Pending) {
        if (strokeQueue.size >= MAX_QUEUED_STROKES) {
            // A stale touch is more dangerous than dropping it; retain the most recent intent.
            strokeQueue.removeFirst()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastQueueWarningMs >= 5_000L) {
                lastQueueWarningMs = now
                PylaLog.w(TAG, "gesture queue saturated; discarded oldest pending touch")
            }
        }
        strokeQueue.addLast(pending)
    }

    private fun restorePending(items: List<Pending>) {
        for (item in items.asReversed()) {
            if (strokeQueue.size >= MAX_QUEUED_STROKES) strokeQueue.removeLast()
            strokeQueue.addFirst(item)
        }
    }

    fun joystickMove(dx: Float, dy: Float) {
        val (cx, cy) = InputCoordinates.joystick()
        handler.post {
            if (!joystickCh.wantDown) { joystickCh.downX = cx; joystickCh.downY = cy }
            joystickCh.wantDown = true
            joystickCh.targetX = cx + dx
            joystickCh.targetY = cy + dy
            pump()
        }
    }

    fun releaseJoystick() {
        handler.post { joystickCh.wantDown = false; pump() }
    }

    fun holdKey(key: String) {
        if (key != "attack") { tap(key); return }
        val (x, y) = InputCoordinates.press(key)
        if (x < 0) return
        holdAttackAt(x.toFloat(), y.toFloat())
    }

    fun holdAttackAt(x: Float, y: Float) {
        handler.post {
            pressSeq++
            if (!attackCh.wantDown) { attackCh.downX = x; attackCh.downY = y }
            attackCh.wantDown = true
            attackCh.targetX = x
            attackCh.targetY = y
            pump()
        }
    }

    fun pressAndRelease(x: Float, y: Float, holdMs: Long) {
        handler.post {
            // Normal combat presses are discrete PC-style taps. Keeping them on the
            // continuous attack channel caused repeated attack() calls to cancel each
            // other's release and turn into one long hold, which is not equivalent to
            // the PC click/touch_down/touch_up sequence.
            enqueueStroke(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000)))
            pump()
        }
    }

    fun releaseKey(key: String) {
        if (key != "attack") return
        handler.post {
            pressSeq++
            attackCh.wantDown = false
            pump()
        }
    }

    private fun pump() {
        if (gestureInFlight) return
        val channels = listOf(joystickCh, attackCh)
        val anyChannelUpdate = channels.any { it.needsUpdate() }
        if (!anyChannelUpdate && strokeQueue.isEmpty()) return

        val builder = GestureDescription.Builder()
        var strokeCount = 0
        val queuedForGesture = ArrayList<Pending>()

        for (ch in channels) {
            val stroke = strokeFor(ch) ?: continue
            builder.addStroke(stroke)
            strokeCount++
        }

        while (strokeQueue.isNotEmpty() && strokeCount < MAX_STROKES_PER_GESTURE) {
            val p = strokeQueue.removeFirst()
            queuedForGesture.add(p)
            val path = Path().apply { moveTo(sx(p.x1), sy(p.y1)); lineTo(sx(p.x2), sy(p.y2)) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, p.durationMs, false))
            strokeCount++
        }

        if (strokeCount == 0) return

        val gesture = try { builder.build() } catch (t: Throwable) {
            PylaLog.w(TAG, "gesture build failed: ${t.message}")
            channels.forEach { it.reset() }
            restorePending(queuedForGesture)
            handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
            return
        }

        gestureInFlight = true
        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    gestureInFlight = false
                    pump()
                }

                override fun onCancelled(g: GestureDescription?) {
                    joystickCh.reset()
                    attackCh.reset()
                    restorePending(queuedForGesture)
                    gestureInFlight = false
                    handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
                }
            }, handler)
        } catch (t: Throwable) {
            PylaLog.w(TAG, "dispatch failed: ${t.message}")
            false
        }
        if (!accepted) {
            gestureInFlight = false
            joystickCh.reset()
            attackCh.reset()
            restorePending(queuedForGesture)
            handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
        }
    }

    private fun sx(v: Float): Float = InputCoordinates.toScreenX(v)
    private fun sy(v: Float): Float = InputCoordinates.toScreenY(v)

    private fun strokeFor(ch: Channel): GestureDescription.StrokeDescription? {
        val prev = ch.lastStroke
        return when {
            ch.wantDown && !ch.isDown -> {
                val path = transitionPath(ch.downX, ch.downY, ch.targetX, ch.targetY)
                val s = GestureDescription.StrokeDescription(path, 0L, MOVE_DURATION_MS, true)
                ch.isDown = true
                ch.curX = ch.targetX; ch.curY = ch.targetY
                ch.lastStroke = s
                s
            }
            !ch.wantDown && ch.isDown && prev != null -> {
                val path = stationaryPath(ch.curX, ch.curY)
                val s = prev.continueStroke(path, 0L, RELEASE_DURATION_MS, false)
                ch.reset()
                s
            }
            ch.isDown && prev != null -> {
                val targetChanged = !samePoint(ch.curX, ch.curY, ch.targetX, ch.targetY)
                val path = if (targetChanged) {
                    transitionPath(ch.curX, ch.curY, ch.targetX, ch.targetY)
                } else {
                    keepAlivePath(ch)
                }
                val duration = if (targetChanged) MOVE_DURATION_MS else HOLD_DURATION_MS
                val s = prev.continueStroke(path, 0L, duration, true)
                // Both paths end at the nominal target. Keeping this exact is essential:
                // continueStroke rejects/cancels a chain if the next path does not start where the
                // previous one really ended.
                ch.curX = ch.targetX; ch.curY = ch.targetY
                ch.lastStroke = s
                s
            }
            else -> null
        }
    }

    private fun samePoint(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        kotlin.math.abs(x1 - x2) < 0.01f && kotlin.math.abs(y1 - y2) < 0.01f

    private fun transitionPath(fromX: Float, fromY: Float, toX: Float, toY: Float): Path =
        Path().apply {
            moveTo(sx(fromX), sy(fromY))
            lineTo(sx(toX), sy(toY))
        }

    private fun stationaryPath(x: Float, y: Float): Path = Path().apply {
        val px = sx(x); val py = sy(y)
        moveTo(px, py); lineTo(px, py)
    }

    /**
     * Keeps a stationary pointer alive without changing its final position.
     *
     * Some Android builds treat a completely zero-length continuation as a completed tap. A tiny
     * radial out-and-back path is non-degenerate, but ends at exactly the same point where it began.
     * Therefore the next continueStroke starts at the required endpoint and the OS never cancels or
     * re-presses the joystick. The excursion is one screen pixel along the current joystick radius,
     * so it cannot introduce sideways direction wobble.
     */
    private fun keepAlivePath(ch: Channel): Path {
        val px = sx(ch.curX); val py = sy(ch.curY)
        val centerX = sx(ch.downX); val centerY = sy(ch.downY)
        val dx = px - centerX; val dy = py - centerY
        val magnitude = kotlin.math.hypot(dx, dy)
        val ux = if (magnitude >= 0.5f) dx / magnitude else 1f
        val uy = if (magnitude >= 0.5f) dy / magnitude else 0f
        return Path().apply {
            moveTo(px, py)
            lineTo(px + ux * KEEP_ALIVE_PX, py + uy * KEEP_ALIVE_PX)
            lineTo(px, py)
        }
    }

    companion object {
        private const val TAG = "PylaInput"
        // Direction changes complete quickly; steady holds use longer closed continuations to reduce
        // main-thread dispatch handoffs while preserving a continuously-down pointer.
        private const val MOVE_DURATION_MS = 32L
        private const val HOLD_DURATION_MS = 96L
        private const val KEEP_ALIVE_PX = 1f
        private const val RELEASE_DURATION_MS = 16L
        private const val MAX_STROKES_PER_GESTURE = 8
        private const val MAX_QUEUED_STROKES = 16
        private const val DISPATCH_RETRY_MS = 24L

        @Volatile private var instance: InputService? = null
        @Volatile private var foregroundPkg: String? = null

        fun get(): InputService? = instance
        fun isConnected(): Boolean = instance != null
        fun foregroundPackage(): String? = foregroundPkg
    }
}
