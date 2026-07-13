package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.input.InputService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FrameSnapshot(val width: Int, val height: Int, val argb: IntArray) {
    fun ageMs(): Long {
        if (createdAtNs == 0L) return 0
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNs)
    }
    @Volatile var createdAtNs: Long = 0L
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

    fun screenshot(): FrameSnapshot {
        var snap = latestFrameNow()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (snap == null) {
            if (System.nanoTime() > deadline) throw IllegalStateException("No frame from MediaProjection")
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
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
        return frame to (frame?.createdAtNs ?: 0L)
    }

    private var copyBuffer: IntArray = IntArray(0)

    private fun latestFrameNow(): FrameSnapshot? {
        val src = captureService.latestFrame() ?: return null
        val argb = src.rgbBuffer ?: return null
        val len = src.width * src.height
        if (copyBuffer.size != len) copyBuffer = IntArray(len)
        System.arraycopy(argb, 0, copyBuffer, 0, len)
        val s = FrameSnapshot(src.width, src.height, copyBuffer)
        s.createdAtNs = System.nanoTime()
        return s
    }

    fun move(x: Float, y: Float) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "move ignored: InputService not connected"); return }
        input.joystickMove(x, y)
        areWeMoving = true
    }

    fun releaseMovement() {
        val input = InputService.get() ?: return
        if (areWeMoving) {
            input.releaseJoystick()
            areWeMoving = false
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        val input = InputService.get()
        if (input == null) { PylaLog.w(TAG, "swipe ignored: InputService not connected"); return }
        PylaLog.p(TAG, "swipe ($x1,$y1) -> ($x2,$y2) ${durationMs}ms")
        input.swipeAt(x1, y1, x2, y2, durationMs)
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
        if (touchDown && touchUp && key in COMBAT_KEYS) {
            val input = InputService.get()
            if (input == null) { PylaLog.w(TAG, "press ignored: InputService not connected"); return }
            input.pressAndRelease(bx.toFloat(), by.toFloat(), delayMs + 40)
            return
        }
        click(bx.toFloat(), by.toFloat(), delayMs, touchUp, touchDown)
    }

    val joystickX: Float get() = InputCoordinates.joystick().first
    val joystickY: Float get() = InputCoordinates.joystick().second

    companion object {
        private const val TAG = "PylaWindowController"
        const val FRAME_STALE_TIMEOUT_MS = 5_000L
        private val COMBAT_KEYS = setOf("attack", "super", "gadget", "hypercharge")
    }
}