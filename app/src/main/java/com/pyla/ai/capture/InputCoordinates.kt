package com.pyla.ai.capture

import com.pyla.ai.config.PylaConfig

object InputCoordinates {

    private const val BASE_W = 1920
    private const val BASE_H = 1080

    enum class Anchor { LEFT, CENTER, RIGHT }

    private data class Spot(val x: Int, val y: Int, val anchor: Anchor)

    private val pressSpots: Map<String, Spot> = mapOf(
        // Keep these coordinates aligned with cfg/buttons_config.toml and the PC port.
        "hypercharge" to Spot(1400, 900, Anchor.RIGHT),
        "gadget" to Spot(1640, 990, Anchor.RIGHT),
        "attack" to Spot(1725, 800, Anchor.RIGHT),
        "proceed" to Spot(1660, 980, Anchor.RIGHT),
        "super" to Spot(1510, 880, Anchor.RIGHT),
        "play_again" to Spot(1360, 920, Anchor.RIGHT),
        "continue_or_equip" to Spot(700, 1000, Anchor.CENTER),
        "middle_got_it" to Spot(960, 980, Anchor.CENTER),
        "middle" to Spot(960, 540, Anchor.CENTER),
    )

    @Volatile private var captureW: Int = BASE_W
    @Volatile private var captureH: Int = BASE_H
    @Volatile private var screenW: Int = 0
    @Volatile private var screenH: Int = 0

    val widthRatio: Float get() = captureW.toFloat() / BASE_W
    val heightRatio: Float get() = captureH.toFloat() / BASE_H
    val scaleFactor: Float get() = minOf(widthRatio, heightRatio)

    fun setCaptureSize(w: Int, h: Int) {
        captureW = w.coerceAtLeast(1)
        captureH = h.coerceAtLeast(1)
    }

    fun setScreenSize(w: Int, h: Int) {
        screenW = w.coerceAtLeast(1)
        screenH = h.coerceAtLeast(1)
    }

    fun toScreenX(x: Float): Float = if (screenW > 0) x * screenW / captureW else x
    fun toScreenY(y: Float): Float = if (screenH > 0) y * screenH / captureH else y

    fun mapX(baseX: Int, anchor: Anchor): Int {
        val hr = heightRatio
        return when (anchor) {
            Anchor.LEFT -> (baseX * hr).toInt()
            Anchor.CENTER -> (captureW / 2f + (baseX - BASE_W / 2f) * hr).toInt()
            Anchor.RIGHT -> (captureW - (BASE_W - baseX) * hr).toInt()
        }
    }

    fun mapY(baseY: Int): Int = (baseY * heightRatio).toInt()

    fun press(key: String): Pair<Int, Int> {
        val fallback = pressSpots[key] ?: return (-1 to -1)
        // The PC port reads button locations from buttons_config.toml. Keep the same
        // source of truth on Android while retaining Android's aspect-ratio anchors.
        val spot = try {
            val configured = PylaConfig.load("cfg/buttons_config.toml").getIntArray(key)
            if (configured.size >= 2) Spot(configured[0], configured[1], fallback.anchor) else fallback
        } catch (_: Throwable) {
            fallback
        }
        return mapX(spot.x, spot.anchor) to mapY(spot.y)
    }

    fun joystick(): Pair<Float, Float> = (220f * heightRatio) to (870f * heightRatio)

    val captureWidth: Int get() = captureW
    val captureHeight: Int get() = captureH
}
