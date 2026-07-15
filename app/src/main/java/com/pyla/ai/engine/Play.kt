package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.TomlLite
import com.pyla.ai.pyla.NativeFn
import com.pyla.ai.pyla.Py
import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyList
import com.pyla.ai.pyla.PyNone
import com.pyla.ai.pyla.PyTuple
import com.pyla.ai.pyla.PylaScript
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Play(
    val windowController: WindowController,
    mainInfoModelPath: String,
    tileDetectorModelPath: String,
    closeTileDetectorModelPath: String,
    var pylaScript: PylaScript? = null,
) {
    companion object { private const val TAG = "PylaPlay" }

    private fun botConfig(): TomlLite = PylaConfig.load("cfg/bot_config.toml")
    private fun timeConfig(): TomlLite = PylaConfig.load("cfg/time_tresholds.toml")
    private fun lobbyConfig(): TomlLite = PylaConfig.load("cfg/lobby_config.toml")

    private val superCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.super")
    private val gadgetCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.gadget")
    private val hyperchargeCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.hypercharge")

    private val verboseDebug: Boolean get() = PylaUtils.configBool(PylaConfig.load("cfg/debug_settings.toml").opt("verbose_debug"), false)

    private val detectMainInfo: Detect = Detect(
        mainInfoModelPath,
        classes = listOf("enemy", "teammate", "player"),
    )
    private val tileDetectorClasses: List<String> get() = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))
    private val centeredWallDetection: Boolean get() = PylaUtils.configBool(botConfig().opt("centered_wall_detection"), false)
    private val detectTileDetector: Detect? = if (!PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        Detect(tileDetectorModelPath, classes = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))) else null
    private val detectCenteredTileDetector: Detect? = if (PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        Detect(closeTileDetectorModelPath, classes = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))) else null

    private val tileSize: Double get() = botConfig().getDouble("perceived_tile_size", 54.0)
    val tileSizePx: Double get() = tileSize * windowController.scaleFactor
    private val centeredWallCropSize: Int = 640

    private val superTresholdSec: Double get() = timeConfig().getDouble("super", 0.1)
    private val gadgetTresholdSec: Double get() = timeConfig().getDouble("gadget", 0.1)
    private val hyperchargeTresholdSec: Double get() = timeConfig().getDouble("hypercharge", 0.1)
    private val wallsTresholdSec: Double get() = maxOf(timeConfig().getDouble("wall_detection", 0.1), 0.35)
    private val noDetectionProceedDelaySec: Double get() = timeConfig().getDouble("no_detection_proceed", 8.0)

    private val gadgetPixelsMin: Long get() = botConfig().getDouble("gadget_pixels_minimum", 1300.0).toLong()
    private val hyperchargePixelsMin: Long get() = botConfig().getDouble("hypercharge_pixels_minimum", 1800.0).toLong()
    private val superPixelsMin: Long get() = botConfig().getDouble("super_pixels_minimum", 1800.0).toLong()
    private val wallDetectionConfidence: Float get() = botConfig().getDouble("wall_detection_confidence", 0.5).toFloat()
    private val entityDetectionConfidence: Float get() = botConfig().getDouble("entity_detection_confidence", 0.65).toFloat()
    private val secondsToHoldAttackAfterReachingMax: Double get() = botConfig().getDouble("seconds_to_hold_attack_after_reaching_max", 1.5)
    private val minimumMovementDelay: Double get() = botConfig().getDouble("minimum_movement_delay", 0.1)

    private val fixMovementKeys = FixMovementState(
        delayToTrigger = PylaConfig.load("cfg/bot_config.toml").getDouble("unstuck_movement_delay", 2.4),
        duration = PylaConfig.load("cfg/bot_config.toml").getDouble("unstuck_movement_hold_time", 1.4),
    )

    private val brawlersInfo: Map<String, BrawlerInfo> = BrawlersInfo.load()
    private var brawlerRanges: Map<String, Triple<Int, Int, Int>>? = null

    // Python-visible dict of brawler info, built once from the raw JSON.
    private var brawlersInfoPyCache: PyDict? = null

    // Persistent dict shared across frames, matching the PC persistent_data behaviour.
    private val persistentDataPy: PyDict = PyDict(LinkedHashMap<Any, Any>().apply {
        put("time_since_holding_attack", PyNone)
        put("last_gadget_use", 0.0)
    })

    var currentBrawler: String? = null
    private var lastWallsData: List<FloatArray> = emptyList()
    private var lastBushesData: List<FloatArray> = emptyList()
    private var timeSinceWallsChecked: Double = 0.0
    private var timeSincePlayerLastFound: Double = nowSec()
    private var timeSinceGadgetChecked: Double = nowSec()
    private var timeSinceHyperchargeChecked: Double = nowSec()
    private var timeSinceSuperChecked: Double = nowSec()
    private var isGadgetReady: Boolean = false
    private var isHyperchargeReady: Boolean = false
    private var isSuperReady: Boolean = false
    private var timeSinceLastProceeding: Double = nowSec()

    val timeSinceDetections = HashMap<String, Double>().apply {
        put("player", nowSec()); put("enemy", nowSec())
    }

    private var lastMovement: Any? = ""
    private var lastMovementChangeTime: Double = nowSec()

    private var frame: Mat? = null

    private fun nowSec() = System.currentTimeMillis() / 1000.0


    fun getEntityPos(entity: FloatArray): Pair<Double, Double> =
        ((entity[0] + entity[2]) / 2.0) to ((entity[1] + entity[3]) / 2.0)

    fun getDistance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double = hypot(a.first - b.first, a.second - b.second)

    fun canAttackThroughWalls(brawler: String, skillType: String, info: Map<String, BrawlerInfo> = brawlersInfo): Boolean {
        val bi = info[brawler] ?: return false
        return if (skillType == "attack") bi.ignoreWallsForAttacks
        else if (skillType == "super") bi.ignoreWallsForSupers
        else false
    }

    fun mustHoldAttack(brawler: String, info: Map<String, BrawlerInfo> = brawlersInfo): Boolean =
        (info[brawler]?.holdAttack ?: 0.0) > 0

    fun wallsBlockLineOfSight(p1: Pair<Double, Double>, p2: Pair<Double, Double>, walls: List<FloatArray>): Boolean {
        if (walls.isEmpty()) return false
        val minx = minOf(p1.first, p2.first); val maxx = maxOf(p1.first, p2.first)
        val miny = minOf(p1.second, p2.second); val maxy = maxOf(p1.second, p2.second)
        for (w in walls) {
            val x1 = w[0].toDouble(); val y1 = w[1].toDouble(); val x2 = w[2].toDouble(); val y2 = w[3].toDouble()
            if (maxx < x1 || minx > x2 || maxy < y1 || miny > y2) continue
            val rect = Rect(x1.toInt(), y1.toInt(), (x2 - x1).toInt().coerceAtLeast(1), (y2 - y1).toInt().coerceAtLeast(1))
            val clipRet = Imgproc.clipLine(rect, Point(p1.first, p1.second), Point(p2.first, p2.second))
            if (clipRet) return true
        }
        return false
    }

    fun getPlayerHitCircle(playerBox: FloatArray?): Pair<Pair<Double, Double>, Double> {
        val radius = PylaUtils.PLAYER_HIT_CIRCLE_RADIUS * (windowController.scaleFactor.toDouble())
        if (playerBox != null && playerBox.size >= 4) {
            val cx = (playerBox[0] + playerBox[2]) / 2.0
            val cy = playerBox[3].toDouble() - radius
            return (cx to cy) to radius
        }
        return (0.0 to 0.0) to radius
    }

    fun getActualPlayerBox(playerBox: FloatArray?): FloatArray? {
        val (center, radius) = getPlayerHitCircle(playerBox)
        if (center.first == 0.0 && center.second == 0.0 && playerBox == null) return null
        return floatArrayOf(
            (center.first - radius).toFloat(), (center.second - radius).toFloat(),
            (center.first + radius).toFloat(), (center.second + radius).toFloat(),
        )
    }

    fun pointRectDistSq(p: Pair<Double, Double>, rect: FloatArray): Double {
        val x = p.first; val y = p.second
        val x1 = rect[0].toDouble(); val y1 = rect[1].toDouble(); val x2 = rect[2].toDouble(); val y2 = rect[3].toDouble()
        val dx = maxOf(x1 - x, 0.0, x - x2)
        val dy = maxOf(y1 - y, 0.0, y - y2)
        return dx * dx + dy * dy
    }

    fun wallsBlockSweptCircle(p1: Pair<Double, Double>, p2: Pair<Double, Double>, radius: Double, walls: List<FloatArray>): Boolean {
        if (walls.isEmpty()) return false
        val minx = minOf(p1.first, p2.first); val maxx = maxOf(p1.first, p2.first)
        val miny = minOf(p1.second, p2.second); val maxy = maxOf(p1.second, p2.second)
        val r = kotlin.math.ceil(radius).toInt()
        val rSq = radius * radius
        for (w in walls) {
            val x1 = w[0].toInt(); val y1 = w[1].toInt(); val x2 = w[2].toInt(); val y2 = w[3].toInt()
            val ex1 = x1 - r; val ey1 = y1 - r; val ex2 = x2 + r; val ey2 = y2 + r
            if (maxx < ex1 || minx > ex2 || maxy < ey1 || miny > ey2) continue
            val rect = Rect(ex1, ey1, (ex2 - ex1).coerceAtLeast(1), (ey2 - ey1).coerceAtLeast(1))
            val clip = Imgproc.clipLine(rect, Point(p1.first, p1.second), Point(p2.first, p2.second))
            if (clip) {
                val startD = pointRectDistSq(p1, w)
                val endD = pointRectDistSq(p2, w)
                if (startD <= rSq && endD > startD) continue
                return true
            }
        }
        return false
    }

    fun isEnemyHittable(playerPos: Pair<Double, Double>, enemyPos: Pair<Double, Double>, walls: List<FloatArray>, skillType: String): Boolean {
        if (canAttackThroughWalls(currentBrawler ?: "", skillType)) return true
        return !wallsBlockLineOfSight(playerPos, enemyPos, walls)
    }

    fun findClosestEnemy(enemyData: List<FloatArray>?, playerCoords: Pair<Double, Double>, walls: List<FloatArray>, skillType: String): Pair<Pair<Double, Double>?, Double?> {
        if (enemyData.isNullOrEmpty()) return null to null
        var hitClosestD = Double.MAX_VALUE; var hitClosest: Pair<Double, Double>? = null
        var unhitClosestD = Double.MAX_VALUE; var unhitClosest: Pair<Double, Double>? = null
        for (e in enemyData) {
            val ep = getEntityPos(e); val d = getDistance(ep, playerCoords)
            if (isEnemyHittable(playerCoords, ep, walls, skillType)) {
                if (d < hitClosestD) { hitClosestD = d; hitClosest = ep }
            } else {
                if (d < unhitClosestD) { unhitClosestD = d; unhitClosest = ep }
            }
        }
        if (hitClosest != null) return hitClosest to hitClosestD
        if (unhitClosest != null) return unhitClosest to unhitClosestD
        return null to null
    }

    fun findClosestTeammate(teammateData: List<FloatArray>?, playerCoords: Pair<Double, Double>): Pair<Pair<Double, Double>?, Double?> {
        if (teammateData.isNullOrEmpty()) return null to null
        var cd = Double.MAX_VALUE; var ct: Pair<Double, Double>? = null
        for (t in teammateData) {
            val tp = getEntityPos(t); val d = getDistance(tp, playerCoords)
            if (d < cd) { cd = d; ct = tp }
        }
        return ct to cd
    }

    fun loadBrawlerRanges(): Map<String, Triple<Int, Int, Int>> {
        val ratio = windowController.scaleFactor
        val out = HashMap<String, Triple<Int, Int, Int>>()
        for ((name, info) in brawlersInfo) {
            out[name] = Triple(
                (info.safeRange * ratio).toInt(),
                (info.attackRange * ratio).toInt(),
                (info.superRange * ratio).toInt(),
            )
        }
        return out
    }

    private var rangesScale: Float = 0f

    fun getBrawlerRange(brawler: String): Triple<Int, Int, Int> {
        val sf = windowController.scaleFactor
        if (brawlerRanges == null || kotlin.math.abs(sf - rangesScale) > 0.01f) {
            brawlerRanges = loadBrawlerRanges()
            rangesScale = sf
        }
        return brawlerRanges!![brawler] ?: Triple(0, 0, 0)
    }

    fun attack(touchUp: Boolean = true, touchDown: Boolean = true) {
        windowController.press("attack", touchUp = touchUp, touchDown = touchDown)
    }
    fun useSuper() { Log.i(TAG, "Using super"); windowController.press("super"); timeSinceSuperChecked = nowSec(); isSuperReady = false }
    fun useGadget() { Log.i(TAG, "Using gadget"); windowController.press("gadget"); timeSinceGadgetChecked = nowSec(); isGadgetReady = false }
    fun useHypercharge() { Log.i(TAG, "Using hypercharge"); windowController.press("hypercharge"); timeSinceHyperchargeChecked = nowSec(); isHyperchargeReady = false }

    fun isPathBlocked(playerBox: FloatArray?, moveDirection: Any?, walls: List<FloatArray>): Boolean {
        val m = movementToVector(moveDirection) ?: return false
        val mag = hypot(m.first, m.second)
        if (mag < 1) return false
        val distance = tileSize * windowController.scaleFactor
        val dx = m.first / mag * distance; val dy = m.second / mag * distance
        val (center, radius) = getPlayerHitCircle(playerBox)
        if (center.first == 0.0 && center.second == 0.0 && playerBox == null) return false
        val newPos = (center.first + dx) to (center.second + dy)
        return wallsBlockSweptCircle(center, newPos, radius, walls)
    }

    fun isTherePoisonGas(playerBox: FloatArray?, threshold: Int = 7000, areaFromPlayerChecked: Double = 1.5): Map<String, Int> {
        val image = frame ?: return emptyMap()
        val actual = getActualPlayerBox(playerBox) ?: playerBox ?: return emptyMap()
        val px1 = actual[0].toDouble(); val py1 = actual[1].toDouble()
        val px2 = actual[2].toDouble(); val py2 = actual[3].toDouble()
        val pw = maxOf(px2 - px1, 1.0); val ph = maxOf(py2 - py1, 1.0)
        val minX = maxOf(px1 - pw * areaFromPlayerChecked, 0.0).toInt()
        val maxX = minOf(px2 + pw * areaFromPlayerChecked, image.cols().toDouble()).toInt()
        val minY = maxOf(py1 - ph * areaFromPlayerChecked, 0.0).toInt()
        val maxY = minOf(py2 + ph * areaFromPlayerChecked, image.rows().toDouble()).toInt()
        if (minX >= maxX || minY >= maxY) return mapOf("up" to 0, "down" to 0, "left" to 0, "right" to 0)
        val roi = Mat(image, Rect(minX, minY, maxX - minX, maxY - minY))
        val hsv = Mat(); Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        org.opencv.core.Core.inRange(hsv,
            org.opencv.core.Scalar(30.0, 90.0, 221.0),
            org.opencv.core.Scalar(57.0, 114.0, 235.0), mask)
        val center = getEntityPos(actual)
        val rw = maxX - minX; val rh = maxY - minY
        val localX = PylaUtils.clamp((center.first - minX).toFloat(), 0f, rw.toFloat()).toInt()
        val localY = PylaUtils.clamp((center.second - minY).toFloat(), 0f, rh.toFloat()).toInt()
        val out = mutableMapOf(
            "up" to PylaUtils.countMaskPixels(mask, 0, 0, rw, localY),
            "down" to PylaUtils.countMaskPixels(mask, 0, localY, rw, rh),
            "left" to PylaUtils.countMaskPixels(mask, 0, 0, localX, rh),
            "right" to PylaUtils.countMaskPixels(mask, localX, 0, rw, rh),
        )
        roi.release(); hsv.release(); mask.release()
        val scaledThreshold = threshold * pixelAreaScale()
        return out.mapValues { if (it.value > scaledThreshold) it.value else 0 }
    }


    fun movementToVector(m: Any?): Pair<Double, Double>? {
        if (m is Pair<*, *>) {
            val x = m.first as? Number ?: return null
            val y = m.second as? Number ?: return null
            return x.toDouble() to y.toDouble()
        }
        if (m is FloatArray && m.size == 2) return m[0].toDouble() to m[1].toDouble()
        if (m is IntArray && m.size == 2) return m[0].toDouble() to m[1].toDouble()
        return null
    }

    fun rotateMovement(m: Pair<Double, Double>, angleRad: Double): Pair<Double, Double> {
        val c = cos(angleRad); val s = sin(angleRad)
        return (m.first * c - m.second * s) to (m.first * s + m.second * c)
    }

    fun movementDirectionKey(m: Pair<Double, Double>): Int? {
        val mag = hypot(m.first, m.second)
        if (mag < 1) return null
        val angle = atan2(m.second, m.first)
        return (angle / (PI / 8)).toInt() and 15
    }

    fun unstuckMovementIfNeeded(movement: Pair<Double, Double>, currentTime: Double = nowSec()): Pair<Double, Double> {
        val dirKey = movementDirectionKey(movement)
        if (dirKey == null) {
            fixMovementKeys.reset()
            timeSinceDifferentMovement = currentTime
            return movement
        }
        if (fixMovementKeys.toggled) {
            if (currentTime - fixMovementKeys.startedAt > fixMovementKeys.duration) {
                fixMovementKeys.toggled = false
                fixMovementKeys.lastDirectionKey = dirKey
                timeSinceDifferentMovement = currentTime
                return movement
            }
            return fixMovementKeys.fixed
        }
        if (fixMovementKeys.lastDirectionKey != dirKey) {
            fixMovementKeys.lastDirectionKey = dirKey
            fixMovementKeys.rotationSign = 1
            fixMovementKeys.rotationAngleStep = 1.0
            timeSinceDifferentMovement = currentTime
        }
        if (currentTime - timeSinceDifferentMovement > fixMovementKeys.delayToTrigger) {
            fixMovementKeys.rotationSign *= -1
            val step = fixMovementKeys.rotationAngleStep
            val rotated = rotateMovement(movement, fixMovementKeys.rotationSign * step * PI / 4)
            if (fixMovementKeys.rotationSign > 0) {
                fixMovementKeys.rotationAngleStep += 1.0
                if (fixMovementKeys.rotationAngleStep > fixMovementKeys.maxRotationAngleStep)
                    fixMovementKeys.rotationAngleStep = 1.0
            }
            fixMovementKeys.fixed = rotated
            fixMovementKeys.toggled = true
            fixMovementKeys.startedAt = currentTime
            return rotated
        }
        return movement
    }

    private var timeSinceDifferentMovement: Double = nowSec()

    fun clampMovement(m: Pair<Double, Double>): Pair<Double, Double> {
        val tx = PylaUtils.clamp(m.first.toFloat(), (-PylaUtils.JOYSTICK_RADIUS * windowController.widthRatio).toFloat(), (PylaUtils.JOYSTICK_RADIUS * windowController.widthRatio).toFloat())
        val ty = PylaUtils.clamp(m.second.toFloat(), (-PylaUtils.JOYSTICK_RADIUS * windowController.heightRatio).toFloat(), (PylaUtils.JOYSTICK_RADIUS * windowController.heightRatio).toFloat())
        return tx.toDouble() to ty.toDouble()
    }

    fun doMovement(movement: Pair<Double, Double>?) {
        if (movement == null) {
            windowController.releaseMovement()
            return
        }
        windowController.move(movement.first.toFloat(), movement.second.toFloat())
    }


    private fun abilityCrop(frame: Mat, area: List<Int>): Mat? {
        if (area.size < 4) return null
        val wr = windowController.widthRatio.toDouble(); val hr = windowController.heightRatio.toDouble()
        val w = frame.cols(); val h = frame.rows()
        val x1 = minOf(area[0] * wr, w - (1920 - area[0]) * hr).toInt().coerceIn(0, w)
        val x2 = maxOf(area[2] * wr, w - (1920 - area[2]) * hr).toInt().coerceIn(0, w)
        val y1 = (area[1] * hr).toInt().coerceIn(0, h)
        val y2 = (area[3] * hr).toInt().coerceIn(0, h)
        if (x2 <= x1 || y2 <= y1) return null
        return Mat(frame, Rect(x1, y1, x2 - x1, y2 - y1))
    }

    private fun pixelAreaScale(): Double =
        (windowController.widthRatio.toDouble() * windowController.heightRatio.toDouble()).coerceAtLeast(0.01)

    fun checkIfSuperReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, superCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(17.0, 170.0, 200.0), doubleArrayOf(27.0, 255.0, 255.0))
        crop.release()
        val min = superPixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "super yellow=$n (min=${min.toInt()})")
        return n > min
    }

    fun checkIfGadgetReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, gadgetCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(57.0, 219.0, 165.0), doubleArrayOf(62.0, 255.0, 255.0))
        crop.release()
        val min = gadgetPixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "gadget green=$n (min=${min.toInt()})")
        return n > min
    }

    fun checkIfHyperchargeReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, hyperchargeCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(137.0, 158.0, 159.0), doubleArrayOf(179.0, 255.0, 255.0))
        crop.release()
        val min = hyperchargePixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "hypercharge purple=$n (min=${min.toInt()})")
        return n > min
    }


    private fun getCenteredWallCrop(frame: Mat, playerData: List<FloatArray>?): Triple<Mat, Int, Int> {
        val fh = frame.rows(); val fw = frame.cols()
        val size = centeredWallCropSize
        val (cx, cy) = if (!playerData.isNullOrEmpty()) {
            val e = playerData[0]; ((e[0] + e[2]) / 2.0) to ((e[1] + e[3]) / 2.0)
        } else (fw / 2.0) to (fh / 2.0)
        val x0 = PylaUtils.clamp((cx - size / 2).toFloat(), 0f, (fw - size).toFloat()).toInt()
        val y0 = PylaUtils.clamp((cy - size / 2).toFloat(), 0f, (fh - size).toFloat()).toInt()
        val crop = Mat(frame, Rect(x0, y0, size, size))
        return Triple(crop, x0, y0)
    }

    private fun getTileData(frame: Mat, playerData: List<FloatArray>?): Map<String, MutableList<FloatArray>> {
        if (centeredWallDetection && detectCenteredTileDetector != null) {
            val (crop, ox, oy) = getCenteredWallCrop(frame, playerData)
            val td = detectCenteredTileDetector.detectObjects(crop, confThresh = wallDetectionConfidence)
            crop.release()
            return offsetTileData(td, ox, oy)
        }
        return detectTileDetector!!.detectObjects(frame, confThresh = wallDetectionConfidence)
    }

    private fun offsetTileData(td: Map<String, MutableList<FloatArray>>, ox: Int, oy: Int): Map<String, MutableList<FloatArray>> {
        if (ox == 0 && oy == 0) return td
        val out = HashMap<String, MutableList<FloatArray>>()
        for ((cls, boxes) in td) {
            val shifted = boxes.map { floatArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }.toMutableList()
            out[cls] = shifted
        }
        return out
    }

    private fun processTileData(td: Map<String, MutableList<FloatArray>>): Pair<List<FloatArray>, List<FloatArray>> {
        val walls = ArrayList<FloatArray>(); val bushes = ArrayList<FloatArray>()
        for ((cls, boxes) in td) {
            if (!cls.contains("bush")) walls.addAll(boxes) else bushes.addAll(boxes)
        }
        return walls to bushes
    }


    fun getMainData(frame: Mat): MutableMap<String, MutableList<FloatArray>> =
        detectMainInfo.detectObjects(frame, confThresh = entityDetectionConfidence) as MutableMap<String, MutableList<FloatArray>>

    fun validateGameData(data: MutableMap<String, MutableList<FloatArray>>?): MutableMap<String, MutableList<FloatArray>>? {
        if (data == null) return null
        if (!data.containsKey("player")) return null
        data.getOrPut("enemy") { ArrayList() }
        data.getOrPut("teammate") { ArrayList() }
        data.getOrPut("wall") { ArrayList() }
        data.getOrPut("bush") { ArrayList() }
        return data
    }

    fun trackNoDetections(data: MutableMap<String, MutableList<FloatArray>>?) {
        for (key in listOf("player", "enemy")) {
            if (data != null && data[key]?.isNotEmpty() == true) {
                timeSinceDetections[key] = nowSec()
            }
        }
    }


    fun main(frame: Mat, brawler: String, state: String?, onStateChange: (String) -> Unit) {
        this.frame = frame
        val currentTime = nowSec()
        var data: MutableMap<String, MutableList<FloatArray>> = getMainData(frame)
        if (currentTime - timeSinceWallsChecked > wallsTresholdSec) {
            val td = getTileData(frame, data["player"])
            val (walls, bushes) = processTileData(td)
            timeSinceWallsChecked = currentTime
            lastWallsData = walls; lastBushesData = bushes
            data["wall"] = walls.toMutableList(); data["bush"] = bushes.toMutableList()
        } else {
            data["wall"] = lastWallsData.toMutableList(); data["bush"] = lastBushesData.toMutableList()
        }

        data = validateGameData(data) ?: HashMap()
        trackNoDetections(data)

        val hasPlayer = !data["player"].isNullOrEmpty()
        if (hasPlayer) {
            timeSincePlayerLastFound = currentTime
        }

        if (!hasPlayer || state != "match") {
            if (currentTime - timeSincePlayerLastFound > 1.0) {
                windowController.releaseMovement()
            }
            if (currentTime - timeSinceLastProceeding > noDetectionProceedDelaySec) {
                val currentState = StateFinder.getState(frame)
                if (currentState != "match") {
                    onStateChange(currentState)
                    timeSinceLastProceeding = currentTime
                } else {
                    Log.i(TAG, "haven't detected the player in a while proceeding")
                    windowController.press("proceed")
                    timeSinceLastProceeding = currentTime
                }
            }
            return
        }

        timeSinceLastProceeding = currentTime

        if (currentTime - timeSinceHyperchargeChecked > hyperchargeTresholdSec) {
            isHyperchargeReady = checkIfHyperchargeReady(frame)
            timeSinceHyperchargeChecked = currentTime
        }
        if (currentTime - timeSinceGadgetChecked > gadgetTresholdSec) {
            isGadgetReady = checkIfGadgetReady(frame)
            timeSinceGadgetChecked = currentTime
        }
        if (currentTime - timeSinceSuperChecked > superTresholdSec) {
            isSuperReady = checkIfSuperReady(frame)
            timeSinceSuperChecked = currentTime
        }

        currentBrawler = brawler
        val movement = computeScriptMovement(brawler, data)
        doMovement(movement)
    }

    private fun computeScriptMovement(brawler: String, data: Map<String, MutableList<FloatArray>>): Pair<Double, Double>? {
        val script = pylaScript ?: return null
        val playerBox = data["player"]?.firstOrNull() ?: return null
        val context = buildContext(brawler, playerBox, data)

        val raw = script.run(context)
        var move = pyToPair(raw)
        if (move == null) {
            lastMovement = ""
            return null
        }
        move = clampMovement(move)
        val currentTime = nowSec()
        if (!movementEquals(move, lastMovement)) {
            if (currentTime - lastMovementChangeTime >= minimumMovementDelay) {
                lastMovement = move
                lastMovementChangeTime = currentTime
            } else {
                val prev = lastMovement
                if (prev is Pair<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    move = prev as Pair<Double, Double>
                } else {
                    return null
                }
            }
        } else {
            lastMovementChangeTime = currentTime
        }
        return unstuckMovementIfNeeded(move, currentTime)
    }

    private fun movementEquals(a: Pair<Double, Double>, b: Any?): Boolean {
        if (b !is Pair<*, *>) return false
        return a.first == b.first && a.second == b.second
    }

    // ---------- .pyla context bridge ----------

    private fun brawlersInfoPy(): PyDict {
        brawlersInfoPyCache?.let { return it }
        val outer = LinkedHashMap<Any, Any>()
        try {
            val file = PylaConfig.resolve("cfg/brawlers_info.json")
            if (file.exists()) {
                val root = JSONObject(file.readText())
                for (key in root.keys()) {
                    outer[key] = jsonToPy(root.get(key))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "brawlers_info parse: ${t.message}")
        }
        val d = PyDict(outer)
        brawlersInfoPyCache = d
        return d
    }

    private fun jsonToPy(v: Any?): Any = when (v) {
        null, JSONObject.NULL -> PyNone
        is JSONObject -> {
            val m = LinkedHashMap<Any, Any>()
            for (k in v.keys()) m[k] = jsonToPy(v.get(k))
            PyDict(m)
        }
        is JSONArray -> {
            val l = ArrayList<Any>()
            for (i in 0 until v.length()) l.add(jsonToPy(v.get(i)))
            PyList(l)
        }
        is Boolean -> v
        is Int -> v.toLong()
        is Long -> v
        is Double -> v
        is Float -> v.toDouble()
        is String -> v
        is Number -> v.toDouble()
        else -> v.toString()
    }

    private fun boxToPy(box: FloatArray): PyList =
        PyList(box.map { it.toDouble() as Any }.toMutableList())

    private fun boxesToPy(boxes: List<FloatArray>): PyList =
        PyList(boxes.map { boxToPy(it) as Any }.toMutableList())

    private fun pyToBox(v: Any?): FloatArray {
        val items = when (v) {
            is PyList -> v.items
            is PyTuple -> v.items
            else -> return FloatArray(0)
        }
        return FloatArray(items.size) { Py.toDouble(items[it]).toFloat() }
    }

    private fun pyToBoxes(v: Any?): List<FloatArray> {
        val items = when (v) {
            is PyList -> v.items
            is PyTuple -> v.items
            else -> return emptyList()
        }
        return items.map { pyToBox(it) }
    }

    private fun pairToPy(p: Pair<Double, Double>): PyTuple = PyTuple(listOf(p.first, p.second))

    private fun pyToPair(v: Any?): Pair<Double, Double>? {
        val items = when (v) {
            is PyTuple -> v.items
            is PyList -> v.items
            else -> return null
        }
        if (items.size != 2) return null
        val x = items[0]; val y = items[1]
        if (!Py.isNumber(x) || !Py.isNumber(y)) return null
        return Py.toDouble(x) to Py.toDouble(y)
    }

    private fun buildContext(brawler: String, playerBox: FloatArray, data: Map<String, MutableList<FloatArray>>): Map<String, Any> {
        val ctx = HashMap<String, Any>()

        ctx["player_data"] = boxToPy(playerBox)
        ctx["enemy_data"] = boxesToPy(data["enemy"] ?: emptyList())
        ctx["teammate_data"] = boxesToPy(data["teammate"] ?: emptyList())
        ctx["walls"] = boxesToPy(data["wall"] ?: emptyList())
        ctx["bushes"] = boxesToPy(data["bush"] ?: emptyList())
        ctx["brawler"] = brawler
        ctx["current_brawler"] = currentBrawler ?: PyNone
        ctx["brawlers_info"] = brawlersInfoPy()
        ctx["is_gadget_ready"] = isGadgetReady
        ctx["is_hypercharge_ready"] = isHyperchargeReady
        ctx["is_super_ready"] = isSuperReady
        ctx["TILE_SIZE"] = tileSizePx
        ctx["JOYSTICK_RADIUS"] = PylaUtils.JOYSTICK_RADIUS.toLong()
        ctx["width"] = 1920L
        ctx["height"] = 1080L
        ctx["debug"] = verboseDebug
        ctx["seconds_to_hold_attack_after_reaching_max"] = secondsToHoldAttackAfterReachingMax
        ctx["persistent_data"] = persistentDataPy
        ctx["last_movement"] = lastMovement?.let { if (it is Pair<*, *>) pyToPair(pairAsPy(it)) ?: PyNone else PyNone } ?: PyNone
        ctx["last_movement_change_time"] = lastMovementChangeTime

        ctx["get_entity_pos"] = NativeFn("get_entity_pos") { a, _ -> pairToPy(getEntityPos(pyToBox(a[0]))) }
        ctx["get_distance"] = NativeFn("get_distance") { a, _ ->
            getDistance(pyToPair(a[0]) ?: (0.0 to 0.0), pyToPair(a[1]) ?: (0.0 to 0.0))
        }
        ctx["get_actual_player_box"] = NativeFn("get_actual_player_box") { a, _ ->
            getActualPlayerBox(pyToBox(a[0]))?.let { boxToPy(it) } ?: PyNone
        }
        ctx["get_brawler_range"] = NativeFn("get_brawler_range") { a, _ ->
            val (s, at, su) = getBrawlerRange(a[0] as? String ?: brawler)
            PyList(mutableListOf(s.toLong(), at.toLong(), su.toLong()))
        }
        ctx["is_there_enemy"] = NativeFn("is_there_enemy") { a, _ ->
            val v = a.getOrNull(0)
            when (v) { is PyList -> v.items.isNotEmpty(); is PyTuple -> v.items.isNotEmpty(); else -> false }
        }
        ctx["attack"] = NativeFn("attack") { a, kw ->
            val tu = boolArg(kw["touch_up"] ?: a.getOrNull(0), true)
            val td = boolArg(kw["touch_down"] ?: a.getOrNull(1), true)
            attack(tu, td); PyNone
        }
        ctx["use_hypercharge"] = NativeFn("use_hypercharge") { _, _ -> useHypercharge(); PyNone }
        ctx["use_super"] = NativeFn("use_super") { _, _ -> useSuper(); PyNone }
        ctx["use_gadget"] = NativeFn("use_gadget") { _, _ -> useGadget(); PyNone }
        ctx["get_random_movement"] = NativeFn("get_random_movement") { _, _ ->
            PyTuple(listOf(Random.nextInt(-75, 76).toLong(), Random.nextInt(-75, 76).toLong()))
        }
        ctx["must_brawler_hold_attack"] = NativeFn("must_brawler_hold_attack") { a, _ ->
            mustHoldAttack(a.getOrNull(0) as? String ?: brawler)
        }
        ctx["find_closest_enemy"] = NativeFn("find_closest_enemy") { a, _ ->
            val enemies = pyToBoxes(a.getOrNull(0))
            val pc = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val walls = pyToBoxes(a.getOrNull(2))
            val skill = a.getOrNull(3) as? String ?: "attack"
            val (coords, dist) = findClosestEnemy(enemies, pc, walls, skill)
            PyTuple(listOf(coords?.let { pairToPy(it) } ?: PyNone, dist ?: PyNone))
        }
        ctx["find_closest_teammate"] = NativeFn("find_closest_teammate") { a, _ ->
            val teammates = pyToBoxes(a.getOrNull(0))
            val pc = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val (coords, dist) = findClosestTeammate(teammates, pc)
            PyTuple(listOf(coords?.let { pairToPy(it) } ?: PyNone, dist ?: PyNone))
        }
        ctx["is_there_poison_gas"] = NativeFn("is_there_poison_gas") { a, kw ->
            val box = pyToBox(a.getOrNull(0))
            val threshold = (kw["threshold"] ?: a.getOrNull(1))?.let { Py.toLong(it).toInt() } ?: 7000
            val area = (kw["area_from_player_checked"] ?: a.getOrNull(2))?.let { Py.toDouble(it) } ?: 1.5
            val gas = isTherePoisonGas(box, threshold, area)
            val m = LinkedHashMap<Any, Any>()
            m["up"] = (gas["up"] ?: 0).toLong()
            m["down"] = (gas["down"] ?: 0).toLong()
            m["left"] = (gas["left"] ?: 0).toLong()
            m["right"] = (gas["right"] ?: 0).toLong()
            PyDict(m)
        }
        ctx["is_path_blocked"] = NativeFn("is_path_blocked") { a, _ ->
            val box = pyToBox(a.getOrNull(0))
            val move = pyToPair(a.getOrNull(1))
            val walls = pyToBoxes(a.getOrNull(2))
            isPathBlocked(box, move, walls)
        }
        ctx["is_enemy_hittable"] = NativeFn("is_enemy_hittable") { a, _ ->
            val pp = pyToPair(a.getOrNull(0)) ?: (0.0 to 0.0)
            val ep = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val walls = pyToBoxes(a.getOrNull(2))
            val skill = a.getOrNull(3) as? String ?: "attack"
            isEnemyHittable(pp, ep, walls, skill)
        }
        ctx["rotate_movement"] = NativeFn("rotate_movement") { a, _ ->
            val m = pyToPair(a.getOrNull(0)) ?: (0.0 to 0.0)
            pairToPy(rotateMovement(m, Py.toDouble(a[1])))
        }

        return ctx
    }

    private fun pairAsPy(p: Pair<*, *>): PyTuple =
        PyTuple(listOf((p.first as Number).toDouble(), (p.second as Number).toDouble()))

    private fun boolArg(v: Any?, default: Boolean): Boolean = when (v) {
        null -> default
        is Boolean -> v
        else -> Py.truthy(v)
    }
}

class FixMovementState(
    val delayToTrigger: Double,
    val duration: Double,
) {
    @Volatile var toggled: Boolean = false
    @Volatile var startedAt: Double = 0.0
    @Volatile var fixed: Pair<Double, Double> = 0.0 to 0.0
    @Volatile var lastDirectionKey: Int? = null
    @Volatile var rotationSign: Int = 1
    @Volatile var rotationAngleStep: Double = 1.0
    val maxRotationAngleStep: Double = 4.0
    fun reset() {
        toggled = false; lastDirectionKey = null; rotationSign = 1; rotationAngleStep = 1.0
    }
}
