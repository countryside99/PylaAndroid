package com.pyla.ai.engine

import com.pyla.ai.config.PylaConfig
import java.io.File

object DebugPainter {

    private const val TAG = "PylaDebug"
    private var frameCounter: Long = 0L
    private var lastFrameMs: Long = 0L

    fun isEnabled(): Boolean = PylaUtils.configBool(
        PylaConfig.load("cfg/debug_settings.toml").opt("debug_view"), false
    )

    fun isAdvanced(): Boolean = PylaUtils.configBool(
        PylaConfig.load("cfg/debug_settings.toml").opt("advanced_debug_visuals"), false
    )

    fun isRecording(): Boolean = PylaUtils.configBool(
        PylaConfig.load("cfg/debug_settings.toml").opt("record_debug_preview_clips"), false
    )

    fun getFps(): Int = PylaConfig.load("cfg/debug_settings.toml").getInt("debug_view_fps", 15)

    fun logFrame(
        brawler: String,
        state: String?,
        playerCount: Int,
        enemyCount: Int,
        teammateCount: Int,
        wallCount: Int,
        bushCount: Int,
        movement: Pair<Double, Double>?,
        superReady: Boolean,
        gadgetReady: Boolean,
        hyperchargeReady: Boolean,
    ) {
        if (!isEnabled()) return
        val now = System.currentTimeMillis()
        frameCounter++
        val fps = if (lastFrameMs > 0 && now > lastFrameMs) (1000.0 / (now - lastFrameMs)).toInt() else 0
        lastFrameMs = now

        val moveStr = if (movement != null)
            "(${"%.0f".format(movement.first)},${"%.0f".format(movement.second)})"
        else "(0,0)"

        val sb = StringBuilder()
        sb.append("#$frameCounter fps=$fps ${brawler}")
        if (state != null) sb.append(" st=$state")
        sb.append(" P=$playerCount E=$enemyCount T=$teammateCount W=$wallCount B=$bushCount")
        sb.append(" m=$moveStr")
        if (superReady) sb.append(" S")
        if (gadgetReady) sb.append(" G")
        if (hyperchargeReady) sb.append(" HC")

        if (isAdvanced()) {
            sb.append(" thresh={W:${"%.2f".format(
                PylaConfig.load("cfg/bot_config.toml").getDouble("wall_detection_confidence", 0.5)
            )} E:${"%.2f".format(
                PylaConfig.load("cfg/bot_config.toml").getDouble("entity_detection_confidence", 0.65)
            )}}")
        }

        android.util.Log.i(TAG, sb.toString())
    }

    fun logState(state: String, templateScores: Map<String, Double> = emptyMap()) {
        if (!isEnabled() && !isAdvanced()) return
        if (templateScores.isEmpty()) {
            android.util.Log.i(TAG, "STATE=$state")
        } else {
            val scores = templateScores.entries.joinToString(" ") { (name, s) -> "$name=${"%.3f".format(s)}" }
            android.util.Log.i(TAG, "STATE=$state  $scores")
        }
    }

    fun logConfig(relativePath: String, key: String, value: String) {
        if (!isEnabled()) return
        android.util.Log.i(TAG, "CONFIG $relativePath::$key = $value")
    }

    fun recordFrame(frame: org.opencv.core.Mat) {
        if (!isRecording()) return
        try {
            val dir = File(PylaConfig.root(), "debug_frames")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, String.format("debug_%05d.png", frameCounter))
            org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, frame)
        } catch (_: Exception) {}
    }
}
