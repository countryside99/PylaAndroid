package com.pyla.ai.engine

import android.os.SystemClock
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.input.InputService

class FrameSnapshot(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val capturedAtMs: Long,
) {
    fun ageMs(): Long =
        if (capturedAtMs <= 0L) Long.MAX_VALUE
        else (SystemClock.elapsedRealtime() - capturedAtMs).coerceAtLeast(0L)
}

class WindowController(
    val captureService: CaptureService,
    private val captureWidth: Int = InputCoordinates.captureWidth.coerceAtLeast(1),
    private val captureHeight: Int = InputCoordinates.captureHeight.coerceAtLeast(1),
) {
    init {
        InputCoordinates.setCaptureSize(captureWidth, captureHeight)
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    val widthRatio: Float get() = width.toFloat() / 1920f
    val heightRatio: Float get() = height.toFloat() / 1080f
    val scaleFactor: Float get() = minOf(widthRatio, heightRatio)

    private var areWeMoving = false
    private var lastJoystickX: Float? = null
    private var lastJoystickY: Float? = null
    private val reApplyMovement: Boolean
        get() = PylaUtils.configBool(
            com.pyla.ai.config.PylaConfig.load("cfg/debug_settings.toml").opt("re_apply_movement"),
            false,
        )

    fun screenshot(): FrameSnapshot {
        var snap = latestFrameNow()
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (snap == null || snap.ageMs() > FRAME_STALE_TIMEOUT_MS) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw IllegalStateException("No fresh frame from MediaProjection")
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                throw IllegalStateException("Interrupted while waiting for a capture frame")
            }
            snap = latestFrameNow()
        }
        if (width != snap.width || height != snap.height) {
            width = snap.width
            height = snap.height
            InputCoordinates.setCaptureSize(width, height)
            PylaLog.p(TAG, "capture size now ${width}x${height} (rotation)")
        }
        return snap
    }

    fun getLatestFrame(): Pair<FrameSnapshot?, Long> {
        val frame = latestFrameNow()
        return frame to (frame?.capturedAtMs ?: 0L)
    }

    private var copyBuffer: IntArray? = null

    private fun latestFrameNow(): FrameSnapshot? {
        val timestamp = captureService.latestTimestampMs()
        if (timestamp <= 0L || SystemClock.elapsedRealtime() - timestamp > FRAME_STALE_TIMEOUT_MS) return null
        val copy = captureService.copyLatestFrame(copyBuffer) ?: return null
        copyBuffer = copy.rgbBuffer
        return FrameSnapshot(copy.width, copy.height, copy.rgbBuffer, copy.capturedAtMs)
    }

    fun move(x: Float, y: Float) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "move ignored: InputService not connected"); return }

        // Match PC WindowController.move: press once, then only send a new logical MOVE when the
        // target changes unless debug_settings.re_apply_movement asks for repeated MOVE events.
        // InputService independently keeps the already-down accessibility pointer alive.
        val unchanged = areWeMoving && lastJoystickX == x && lastJoystickY == y
        if (unchanged && !reApplyMovement) return

        input.joystickMove(x, y)
        areWeMoving = true
        lastJoystickX = x
        lastJoystickY = y
    }

    fun releaseMovement() {
        val input = InputService.get() ?: return
        if (areWeMoving) {
            input.releaseJoystick()
            areWeMoving = false
            lastJoystickX = null
            lastJoystickY = null
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "swipe ignored: InputService not connected"); return }
        PylaLog.p(TAG, "swipe ($x1,$y1) -> ($x2,$y2) ${durationMs}ms")
        input.swipeAt(x1, y1, x2, y2, durationMs)
    }

    fun releaseAndTap(x: Float, y: Float, holdMs: Long = 40) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "releaseAndTap ignored: InputService not connected"); return }
        PylaLog.p(TAG, "releaseAndTap at ($x,$y) hold=${holdMs}ms")
        input.releaseJoystick()
        input.tapAt(x, y, holdMs)
    }

    fun click(x: Float, y: Float, delayMs: Long = 20, touchUp: Boolean = true, touchDown: Boolean = true) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "click ignored: InputService not connected"); return }
        if (touchDown) {
            if (touchUp) {
                PylaLog.p(TAG, "click at ($x,$y) hold=${delayMs + 20}ms")
                input.tapAt(x, y, (delayMs + 20))
            } else {
                PylaLog.p(TAG, "hold attack at ($x,$y)")
                input.holdAttackAt(x, y)
            }
        } else if (touchUp) {
            PylaLog.p(TAG, "release attack key")
            input.releaseKey("attack")
        }
    }

    fun press(key: String, delayMs: Long = 20, touchUp: Boolean = true, touchDown: Boolean = true) {
        val (bx, by) = InputCoordinates.press(key)
        if (bx < 0) { PylaLog.w(TAG, "press ignored: unknown key '$key'"); return }
        PylaLog.p(TAG, "press '$key' at ($bx,$by) hold=${delayMs}ms up=$touchUp down=$touchDown")
        BotStatus.action("press '$key' at ($bx,$by)")
        BotStatus.inputConnected = com.pyla.ai.input.InputService.isConnected()
        if (key in COMBAT_KEYS) {
            val input = InputService.get()
            if (input == null) { PylaLog.w(TAG, "press ignored: InputService not connected"); return }
            if (touchDown && touchUp) {
                input.pressAndRelease(bx.toFloat(), by.toFloat(), delayMs + 40)
            } else {
                click(bx.toFloat(), by.toFloat(), delayMs, touchUp, touchDown)
            }
            return
        }
        releaseAndTap(bx.toFloat(), by.toFloat(), delayMs + 20)
    }

    val joystickX: Float get() = InputCoordinates.joystick().first
    val joystickY: Float get() = InputCoordinates.joystick().second

    companion object {
        private const val TAG = "PylaWindowController"
        const val FRAME_STALE_TIMEOUT_MS = 5_000L
        private val COMBAT_KEYS = setOf("attack", "super", "gadget", "hypercharge")
    }
}