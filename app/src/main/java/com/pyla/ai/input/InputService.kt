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
        fun needsUpdate(): Boolean =
            wantDown != isDown || (isDown && (targetX != curX || targetY != curY))
        fun reset() { lastStroke = null; isDown = false }
    }

    private val joystickCh = Channel("joystick")
    private val attackCh = Channel("attack")
    private class Pending(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long)
    private val strokeQueue = ArrayDeque<Pending>()
    private var gestureInFlight = false
    private var pressSeq = 0

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
            if (strokeQueue.size < MAX_QUEUED_STROKES) {
                strokeQueue.addLast(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000)))
            }
            pump()
        }
    }

    fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        handler.post {
            if (strokeQueue.size < MAX_QUEUED_STROKES) {
                strokeQueue.addLast(Pending(x1, y1, x2, y2, durationMs.coerceIn(1, 10_000)))
            }
            pump()
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
            pressSeq++
            val seq = pressSeq
            if (!attackCh.wantDown) { attackCh.downX = x; attackCh.downY = y }
            attackCh.wantDown = true
            attackCh.targetX = x
            attackCh.targetY = y
            pump()
            handler.postDelayed({
                if (pressSeq == seq && attackCh.wantDown) {
                    attackCh.wantDown = false
                    pump()
                }
            }, holdMs.coerceAtLeast(MOVE_DURATION_MS + 20))
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

        for (ch in channels) {
            val stroke = strokeFor(ch) ?: continue
            builder.addStroke(stroke)
            strokeCount++
        }

        while (strokeQueue.isNotEmpty() && strokeCount < MAX_STROKES_PER_GESTURE) {
            val p = strokeQueue.removeFirst()
            val path = Path().apply { moveTo(sx(p.x1), sy(p.y1)); lineTo(sx(p.x2), sy(p.y2)) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, p.durationMs, false))
            strokeCount++
        }

        if (strokeCount == 0) return

        val gesture = try { builder.build() } catch (t: Throwable) {
            PylaLog.w(TAG, "gesture build failed: ${t.message}")
            channels.forEach { it.reset() }
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
                    joystickCh.reset(); attackCh.reset()
                    gestureInFlight = false
                    pump()
                }
            }, handler)
        } catch (t: Throwable) {
            PylaLog.w(TAG, "dispatch failed: ${t.message}")
            false
        }
        if (!accepted) {
            gestureInFlight = false
            channels.forEach { it.reset() }
        }
    }

    private fun sx(v: Float): Float = InputCoordinates.toScreenX(v)
    private fun sy(v: Float): Float = InputCoordinates.toScreenY(v)

    private fun strokeFor(ch: Channel): GestureDescription.StrokeDescription? {
        val prev = ch.lastStroke
        return when {
            ch.wantDown && !ch.isDown -> {
                val path = Path().apply { moveTo(sx(ch.downX), sy(ch.downY)); lineTo(sx(ch.targetX), sy(ch.targetY)) }
                val s = GestureDescription.StrokeDescription(path, 0L, MOVE_DURATION_MS, true)
                ch.isDown = true; ch.curX = ch.targetX; ch.curY = ch.targetY; ch.lastStroke = s
                s
            }
            !ch.wantDown && ch.isDown && prev != null -> {
                val path = Path().apply { moveTo(sx(ch.curX), sy(ch.curY)); lineTo(sx(ch.curX), sy(ch.curY)) }
                val s = prev.continueStroke(path, 0L, RELEASE_DURATION_MS, false)
                ch.reset()
                s
            }
            ch.isDown && prev != null -> {
                val path = Path().apply { moveTo(sx(ch.curX), sy(ch.curY)); lineTo(sx(ch.targetX), sy(ch.targetY)) }
                val s = prev.continueStroke(path, 0L, MOVE_DURATION_MS, true)
                ch.curX = ch.targetX; ch.curY = ch.targetY; ch.lastStroke = s
                s
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "PylaInput"
        private const val MOVE_DURATION_MS = 40L
        private const val RELEASE_DURATION_MS = 16L
        private const val MAX_STROKES_PER_GESTURE = 8
        private const val MAX_QUEUED_STROKES = 16

        @Volatile private var instance: InputService? = null
        @Volatile private var foregroundPkg: String? = null

        fun get(): InputService? = instance
        fun isConnected(): Boolean = instance != null
        fun foregroundPackage(): String? = foregroundPkg
    }
}
