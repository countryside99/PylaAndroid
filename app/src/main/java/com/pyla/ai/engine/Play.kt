package com.pyla.ai.engine

import com.pyla.ai.diagnostics.PipelineTimingTelemetry
import com.pyla.ai.diagnostics.PipelineTimingTelemetry.Stage

import android.os.Handler
import android.os.Looper
import com.pyla.ai.capture.InputCoordinates

import com.pyla.ai.engine.PylaLog
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.TerrainModelInstaller
import com.pyla.ai.config.TomlLite
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.pyla.NativeFn
import com.pyla.ai.pyla.Py
import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyList
import com.pyla.ai.pyla.PyNone
import com.pyla.ai.pyla.PyTuple
import com.pyla.ai.pyla.PylaRunResult
import com.pyla.ai.pyla.PylaRunStatus
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
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class Play(
    val windowController: WindowController,
    mainInfoModelPath: String,
    tileDetectorModelPath: String,
    closeTileDetectorModelPath: String,
    var pylaScript: PylaScript? = null,
    /**
     * Live authority check for gameplay input, bound by the host to its stop/pause state. Every
     * attack DOWN is gated on it at dispatch time, so a queued command cannot fire after the
     * engine stopped producing input.
     */
    private val inputAllowed: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "PylaPlay"
        /**
         * Max age of the capture that a gameplay decision may be derived from. Older frames can no
         * longer prove that the player is where the detections say, so they never authorize input.
         */
        const val MAX_EVIDENCE_FRAME_AGE_MS = 3_000L

        /** Rate limit for reporting the observed screen back to the engine. */
        private const val STATE_REPORT_INTERVAL_MS = 1_000L

        /** In-match entity inferences observed before a cheaper model canvas is considered. */
        private const val ENTITY_TIER_SAMPLES = 6

        /** Fixed reason for a staged persistence/context snapshot that could not be isolated. */
        private const val PERSISTENCE_INVALID = "PERSISTENCE_INVALID"
    }

    private fun botConfig(): TomlLite = PylaConfig.load("cfg/bot_config.toml")
    private fun timeConfig(): TomlLite = PylaConfig.load("cfg/time_tresholds.toml")
    private fun lobbyConfig(): TomlLite = PylaConfig.load("cfg/lobby_config.toml")


    private val gadgetCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.gadget")
    private val hyperchargeCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.hypercharge")

    private val verboseDebug: Boolean get() = PylaUtils.configBool(PylaConfig.load("cfg/debug_settings.toml").opt("verbose_debug"), false)

    /** Reduced-canvas variants still available below the one in use (most detailed first). */
    private var cheaperEntityVariants: List<EntityModelVariants.Variant> = emptyList()
    private var entityVariantDir: File? = null
    private val entityInferenceSamples = ArrayList<Long>(ENTITY_TIER_SAMPLES)
    private val entityModelPath: String = mainInfoModelPath
    private var detectMainInfo: Detect = createEntityDetector(entityModelPath, tune = false)
    private var inferenceTuned = false

    /**
     * Factory entity model: use the reduced-canvas variant that fits this capture geometry (same
     * weights, no padding rows). A custom model, a geometry no variant fits or any installation
     * problem keeps the original 640x640 model exactly as before. Cheaper variants are only
     * adopted later, from inference times measured during real gameplay (see [adaptEntityTier]).
     */
    private fun createEntityDetector(modelPath: String, tune: Boolean): Detect {
        val classes = listOf("enemy", "teammate", "player")
        fun factory() = Detect(modelPath, classes = classes,
            telemetryRole = PerceptionPerformanceTelemetry.ROLE_ENTITY, autoTune = tune)
        try {
            val resolved = File(modelPath).let { if (it.isAbsolute) it else PylaConfig.resolve(modelPath) }
            if (!EntityModelVariants.isFactoryModel(resolved)) return factory()
            // The game always renders in landscape; the engine may be created while the display
            // is still showing the portrait app, so judge the fit on the landscape orientation.
            val cw = InputCoordinates.captureWidth; val ch = InputCoordinates.captureHeight
            val candidates = EntityModelVariants.candidates(maxOf(cw, ch), minOf(cw, ch))
            if (candidates.isEmpty()) return factory()
            val dir = EntityModelVariants.install(com.pyla.ai.PylaApp.ctx(), PylaConfig.root()) ?: return factory()
            val detector = entityVariant(dir, candidates.first(), tune, null)
            entityVariantDir = dir
            cheaperEntityVariants = candidates.drop(1)
            return detector
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "entity_model", "variant_unavailable", t)
            return factory()
        }
    }

    private fun entityVariant(dir: File, variant: EntityModelVariants.Variant, tune: Boolean, threads: Int?): Detect {
        val classes = listOf("enemy", "teammate", "player")
        val detector = if (threads == null) Detect(File(dir, variant.file).absolutePath, classes = classes,
            inputSize = variant.height to variant.width,
            telemetryRole = PerceptionPerformanceTelemetry.ROLE_ENTITY, autoTune = tune)
        else Detect(File(dir, variant.file).absolutePath, classes = classes,
            inputSize = variant.height to variant.width, threads = threads,
            telemetryRole = PerceptionPerformanceTelemetry.ROLE_ENTITY)
        PylaLog.p(TAG, "entity model ${variant.width}x${variant.height} provider=${detector.activeProvider} " +
            "threads=${detector.activeThreads}")
        return detector
    }

    /**
     * Measures threads/providers for both models on this device. Called once by the engine on the
     * first verified lobby: the game has finished loading and loads the CPU about as it does in a
     * match, whereas measurements taken during engine start-up compete with the game's own
     * loading and were observed to be 5-10x inflated and unordered. Runs on the gameplay thread,
     * so no inference is in flight. Any failure leaves the current sessions in place.
     */
    fun tuneInference() {
        if (inferenceTuned) return
        inferenceTuned = true
        try {
            val previous = detectMainInfo
            val variantDir = entityVariantDir
            detectMainInfo = if (variantDir != null) {
                val cw = InputCoordinates.captureWidth; val ch = InputCoordinates.captureHeight
                val candidates = EntityModelVariants.candidates(maxOf(cw, ch), minOf(cw, ch))
                cheaperEntityVariants = candidates.drop(1)
                // No reduced canvas fits this aspect ratio (e.g. 4:3): tune the full model
                // instead of failing and keeping the untuned start-up session.
                candidates.firstOrNull()?.let { entityVariant(variantDir, it, true, null) }
                    ?: createEntityDetector(entityModelPath, tune = true)
            } else createEntityDetector(entityModelPath, tune = true)
            previous.close()
            entityInferenceSamples.clear()
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "tune", "entity_failed", t)
        }
        try {
            val previous = detectTileDetector
            if (previous != null) {
                detectTileDetector = createTileDetector(tune = true)
                previous.close()
            }
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "tune", "tile_failed", t)
        }
    }

    /**
     * Called with every real gameplay entity inference time. When the device cannot run the
     * current canvas within [EntityModelVariants.BUDGET_MS] (median of the first
     * [ENTITY_TIER_SAMPLES] in-match inferences), the next cheaper variant replaces it once per
     * step. Runs on the gameplay thread between two inferences, so no inference is in flight.
     */
    private fun adaptEntityTier(inferenceMs: Long) {
        val dir = entityVariantDir ?: return
        if (cheaperEntityVariants.isEmpty()) return
        entityInferenceSamples.add(inferenceMs)
        if (entityInferenceSamples.size < ENTITY_TIER_SAMPLES) return
        val median = entityInferenceSamples.sorted()[entityInferenceSamples.size / 2]
        entityInferenceSamples.clear()
        if (median <= EntityModelVariants.BUDGET_MS) { cheaperEntityVariants = emptyList(); return }
        val next = cheaperEntityVariants.first()
        try {
            val replacement = entityVariant(dir, next, false, detectMainInfo.activeThreads)
            val previous = detectMainInfo
            detectMainInfo = replacement
            previous.close()
            cheaperEntityVariants = cheaperEntityVariants.drop(1)
            DiagnosticLog.event("engine.entity_model", "median_ms=$median switched_to=${next.width}x${next.height}")
            PylaLog.p(TAG, "entity inference median ${median}ms > ${EntityModelVariants.BUDGET_MS}ms: " +
                "switched to ${next.width}x${next.height}")
        } catch (t: Throwable) {
            cheaperEntityVariants = emptyList()
            DiagnosticLog.failure("engine", "entity_model", "tier_switch_failed", t)
        }
    }

    private val tileDetectorClasses: List<String> get() = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))
    private val centeredWallDetection: Boolean get() = PylaUtils.configBool(botConfig().opt("centered_wall_detection"), false)
    private val configuredTerrainFile = File(tileDetectorModelPath).let {
        if (it.isAbsolute) it else PylaConfig.resolve(tileDetectorModelPath)
    }
    private val resolvedTerrainFile = if (!centeredWallDetection)
        TerrainModelInstaller.resolve(PylaConfig.root(), configuredTerrainFile) else configuredTerrainFile
    // Only the managed factory generation changes variant automatically. A custom legacy box
    // model stays a box model; a custom semantic model requires an explicit config declaration.
    private val useSemanticTerrain = resolvedTerrainFile.canonicalFile != configuredTerrainFile.canonicalFile ||
        PylaUtils.configBool(botConfig().opt("semantic_terrain_model"), false)
    private var detectTileDetector: Detect? = if (!PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        createTileDetector(tune = false) else null

    private fun createTileDetector(tune: Boolean): Detect =
        Detect(resolvedTerrainFile.absolutePath, classes = tileDetectorClasses,
            telemetryRole = PerceptionPerformanceTelemetry.ROLE_TILE,
            semanticTerrain = useSemanticTerrain, terrainTileSize = botConfig().getDouble("perceived_tile_size", 54.0),
            autoTune = tune)
    private val detectCenteredTileDetector: Detect? = if (PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        Detect(closeTileDetectorModelPath, classes = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush")),
            telemetryRole = PerceptionPerformanceTelemetry.ROLE_TILE_CENTERED) else null

    private val tileSize: Double get() = botConfig().getDouble("perceived_tile_size", 54.0)
    val tileSizePx: Double get() = tileSize * windowController.scaleFactor
    private val centeredWallCropSize: Int = 640

    private val superTresholdSec: Double get() = timeConfig().getDouble("super", 0.1)
    private val gadgetTresholdSec: Double get() = timeConfig().getDouble("gadget", 0.1)
    private val hyperchargeTresholdSec: Double get() = timeConfig().getDouble("hypercharge", 0.1)
    private val wallsTresholdSec: Double get() = timeConfig().getDouble("wall_detection", 0.1)

    private val gadgetPixelsMin: Long get() = botConfig().getDouble("gadget_pixels_minimum", 1300.0).toLong()
    private val hyperchargePixelsMin: Long get() = botConfig().getDouble("hypercharge_pixels_minimum", 1800.0).toLong()

    private val wallDetectionConfidence: Float get() = botConfig().getDouble("wall_detection_confidence", 0.5).toFloat()
    private val entityDetectionConfidence: Float get() = botConfig().getDouble("entity_detection_confidence", 0.65).toFloat()
    private val secondsToHoldAttackAfterReachingMax: Double get() = botConfig().getDouble("seconds_to_hold_attack_after_reaching_max", 1.5)

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
    private var lastTerrain = TerrainObservation()
    /** True until a saved attack-control calibration made the per-frame search unnecessary. */
    private var attackControlSearchPending = true
    private var terrainDeferrals = 0
    private var lastClassified: String = "lobby" // a menu verdict is never reused
    private var lastClassifiedFrameMs = 0L
    private var lastClassifyCostMs = 0L
    private var classifyReuses = 0
    private var timeSinceWallsChecked: Double = 0.0
    /** Wall time of the most recent terrain pass; stretches the refresh interval on slow devices. */
    private var lastTerrainCostMs: Long = 0L
    private var timeSincePlayerLastFound: Double = nowSec()
    private var timeSinceGadgetChecked: Double = nowSec()
    private var timeSinceHyperchargeChecked: Double = nowSec()
    private var timeSinceSuperChecked: Double = nowSec()
    private var isGadgetReady: Boolean = false
    private var isHyperchargeReady: Boolean = false
    private var isSuperReady: Boolean = false

    val timeSinceDetections = HashMap<String, Double>().apply {
        put("player", nowSec()); put("enemy", nowSec())
    }

    /**
     * Gameplay admission. A classifier answer alone never authorizes input: it needs fresh, current
     * player evidence from two distinct frames (see [GameplayEvidenceGate]).
     */
    val gameplayEvidence = GameplayEvidenceGate(MAX_EVIDENCE_FRAME_AGE_MS)

    /**
     * Last strategy movement that was actually applied, including an intentional `(0,0)` still. It is
     * a value for the script's own `last_movement` context only: nothing here is replayed as input.
     */
    private var lastMovement: Any? = ""
    private var lastMovementChangeTime: Double = nowSec()

    // ---------- strategy (`.pyla`) intent authority ----------
    //
    // The selected `.pyla` result is the single owner of gameplay decisions. Interpretation runs
    // outside the input monitor and may only *buffer* intents (see [PlaystyleIntentCollector]);
    // [applyPlaystyleOutcome] sends a whole validated decision through the existing guarded calls.
    //
    // The tokens below are the revocation fence for already-dispatched script commands:
    //  - [attackDemandToken] advances on EVERY accepted decision; a finite tap pins the decision
    //    that requested it, so a newer decision (attacking or not) revokes an older queued tap;
    //  - [holdIntentToken] advances only when a hold starts or ends, so consecutive HOLD frames keep
    //    one fluid charge while release/error/stop invalidates the queued DOWN;
    //  - [abilityDemandToken] advances on EVERY accepted decision; every finite ability tap pins the
    //    decision that requested it, so an old SUPER cannot survive a newer gadget-only decision.
    @Volatile private var attackDemandToken: Long = 0L
    @Volatile private var abilityDemandToken: Long = 0L
    @Volatile private var holdIntentToken: Long = 0L
    @Volatile private var holdIntentLive: Boolean = false
    /** Revocation epoch of the script-owned hold timestamp; see [commitPlaystylePersistence]. */
    @Volatile private var scriptHoldEpochRevision: Long = 0L
    private var randomMovementIndex: Int = 0
    // A bounded lease of an already requested charge. A live script-owned timestamp means
    // "continue waiting" in the shipped strategies, not a repeated DOWN on every frame.
    @Volatile private var holdOwnerScript: PylaScript? = null
    @Volatile private var holdOwnerBrawler: String? = null
    @Volatile private var holdOwnerEpochSeconds: Double? = null
    @Volatile private var holdButtonRevision: Long = 0L
    @Volatile private var holdLeaseStartedAtMs: Long = 0L
    @Volatile private var holdLeaseDurationMs: Long = 0L
    @Volatile private var observedHoldLimitSeconds: Double = 3.0

    /** Continuous planner state is reset for compatibility only; no production planning call remains. */
    private val continuousPlanner = ContinuousMovementPlanner()
    private val sceneProgress = SceneProgressTracker()
    @Volatile private var gameplayGeometry: Long = -1L
    private val combatHandler by lazy { Handler(Looper.getMainLooper()) }
    private var attackPulse: Runnable? = null
    private var pulseBrawler: String = ""
    internal var postCombatPulse: (Runnable, Long) -> Unit = { task, delay -> combatHandler.postDelayed(task, delay); Unit }
    internal var removeCombatPulse: (Runnable) -> Unit = { task -> combatHandler.removeCallbacks(task) }

    private var frame: Mat? = null

    internal fun nowSec() = System.currentTimeMillis() / 1000.0

    /** Monotonic "now" for freshness checks; overridable in host tests. */
    internal var monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Current-frame screen classification, injected for testability. Defaults to the production
     * StateFinder; an "unknown" classification must never be produced by a known menu screen.
     */
    private var classificationGeneration = 0L
    private var classificationCapturedAtMs = 0L
    internal var classifyFrame: (Mat) -> String = {
        val observed = StateFinder.getStateObservation(it, classificationGeneration, classificationCapturedAtMs)
        // An ambiguous icon alone is not a positive menu veto. Two fresh valid player frames
        // are still mandatory below; returning unknown here never grants gameplay by itself.
        if (observed.state in MenuIdentityAdmission.AMBIGUOUS_MENU_STATES && !observed.menuConfirmed)
            "unknown" else observed.state
    }


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
        // Compatibility entry point: never reintroduce the old blind mapped attack location.
        synchronized(this) {
            if (!touchDown) {
                if (touchUp) releaseAttackInput("explicit_release")
                return
            }
            val evidence = attackButtonEvidence.current(monotonicNowMs(), InputCoordinates.geometryGeneration) ?: return
            val guard = attackCommandGuard(combatGeneration, evidence.capturedAtMs, touchUp, Thread.currentThread(), evidence.revision)
            if (touchUp) windowController.pressCombatAt(evidence.point.x, evidence.point.y, guard)
            else if (!attackButtonDown && guard()) {
                attackButtonDown = true
                windowController.holdCombatAt(evidence.point.x, evidence.point.y, guard)
            }
        }
    }
    @Synchronized fun useSuper() {
        if (!issueSpecialAbility("super")) return
        Log.i(TAG, "Using super"); timeSinceSuperChecked = nowSec(); isSuperReady = false
    }
    @Synchronized fun useGadget() {
        if (!issueSpecialAbility("gadget")) return
        Log.i(TAG, "Using gadget"); timeSinceGadgetChecked = nowSec(); isGadgetReady = false
    }
    @Synchronized fun useHypercharge() {
        if (!issueSpecialAbility("hypercharge")) return
        Log.i(TAG, "Using hypercharge"); timeSinceHyperchargeChecked = nowSec(); isHyperchargeReady = false
    }

    /**
     * Carry the original finite frame and combat generation through native queue dispatch.
     *
     * [scriptToken] is the strategy decision that requested the ability. A queued ability tap is
     * denied once a later decision requests no ability, so fresh evidence alone cannot revive it.
     * The public `use*` entry points pass `null` and keep their historical behaviour.
     */
    private fun issueSpecialAbility(key: String, scriptToken: Long? = null): Boolean {
        // Readiness is a native precondition, not a hint: a script ability may only consume a
        // positive current-frame signal. `super` additionally needs the real visual cue, so there is
        // no blind mapped-super fallback for a null evidence point.
        val superCue = if (key == "super") superEvidence else null
        when (key) {
            "super" -> if (!isSuperReady || superCue == null) return false
            "gadget" -> if (!isGadgetReady) return false
            "hypercharge" -> if (!isHyperchargeReady) return false
            else -> return false
        }
        if (!combatTargetCurrent()) return false
        val generation = combatGeneration
        val capturedAtMs = if (superCue != null) minOf(activeGameplayFrameMs, superCue.capturedAtMs) else activeGameplayFrameMs
        val issuingThread = Thread.currentThread()
        val combatGuard = attackCommandGuard(generation, capturedAtMs, finite = true, thread = issuingThread)
        val guard = {
            (scriptToken == null || abilityDemandToken == scriptToken) && combatGuard() && activeGameplayTarget && (superCue == null ||
                (InputCoordinates.geometryGeneration == superCue.geometry && superEvidence?.let {
                    it.geometry == superCue.geometry && kotlin.math.abs(it.point.x-superCue.point.x)<3f &&
                        kotlin.math.abs(it.point.y-superCue.point.y)<3f
                } == true))
        }
        if (!guard()) return false
        if (superCue != null) windowController.pressAbilityCombatAt(key, superCue.point.x, superCue.point.y, guard)
        else windowController.press(key, guard = guard)
        // Same consumption bookkeeping the public use* wrappers perform: a successfully admitted
        // request invalidates the readiness sample and refreshes its poll clock, so a queued ability
        // cannot leave stale ready flags behind.
        when (key) {
            "super" -> { timeSinceSuperChecked = nowSec(); isSuperReady = false }
            "gadget" -> { timeSinceGadgetChecked = nowSec(); isGadgetReady = false }
            "hypercharge" -> { timeSinceHyperchargeChecked = nowSec(); isHyperchargeReady = false }
        }
        DiagnosticLog.event("combat.special_request", "ability=$key visual=${superCue != null}")
        return true
    }

    fun isPathBlocked(
        playerBox: FloatArray?,
        moveDirection: Any?,
        walls: List<FloatArray>,
        distanceOverride: Double? = null,
    ): Boolean {
        val m = movementToVector(moveDirection) ?: return false
        val mag = hypot(m.first, m.second)
        if (mag < 1) return false
        val distance = distanceOverride ?: tileSize * windowController.scaleFactor
        val dx = m.first / mag * distance; val dy = m.second / mag * distance
        val (center, radius) = getPlayerHitCircle(playerBox)
        if (center.first == 0.0 && center.second == 0.0 && playerBox == null) return false
        val newPos = (center.first + dx) to (center.second + dy)
        return wallsBlockSweptCircle(center, newPos, radius, lastTerrain.movementObstacles(walls))
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
        // Python uses round(angle / (pi / 8)) % 16. Truncation changes the unstuck
        // direction too early and makes movement corrections noticeably less stable.
        return round(angle / (PI / 8)).toInt() and 15
    }

    /**
     * Retained legacy rotation helper (kept for regression API compatibility).
     *
     * It is *not* part of the production movement path any more: production movement comes only from
     * [DirectedMovementPlanner] behind positive gameplay evidence. Nothing in [main] calls this.
     */
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

    /**
     * Reference-space strategy vector -> capture-space joystick offset, using the shared contract.
     *
     * Kept for callers that predate [PlaystyleContract]; a non-finite or degenerate vector yields
     * `(0, 0)` so nothing can be dispatched from it.
     */
    fun clampMovement(m: Pair<Double, Double>): Pair<Double, Double> {
        val geometry = PlaystyleContract.geometry(
            windowController.width.coerceAtLeast(1),
            windowController.height.coerceAtLeast(1),
        ) ?: return 0.0 to 0.0
        val center = InputCoordinates.joystick()
        val delta = PlaystyleContract.toJoystickDelta(
            x = m.first, y = m.second, geometry = geometry,
            radius = PylaUtils.JOYSTICK_RADIUS.toDouble(),
            centerX = center.first.toDouble(), centerY = center.second.toDouble(),
        ) ?: return 0.0 to 0.0
        return delta.x to delta.y
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

    private data class SuperEvidence(val point: PhoneVisualCues.SuperPoint, val capturedAtMs: Long, val geometry: Long)
    @Volatile private var superEvidence: SuperEvidence? = null
    fun checkIfSuperReady(frame: Mat, capturedAtMs: Long = 0L): Boolean {
        val geometry = InputCoordinates.geometryGeneration
        val policy = SuperReadinessPolicy.fromLegacy(
            botConfig().opt("super_pixels_minimum"),
            lobbyConfig().opt("pixel_counter_crop_area.super"),
        )
        val point = PhoneVisualCues.superReady(frame, policy)
        superEvidence = if (point != null && geometry == InputCoordinates.geometryGeneration)
            SuperEvidence(point, capturedAtMs, geometry) else null
        DiagnosticLog.sample("combat.super_readiness", "ready=${point != null} visual=true")
        return point != null
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

    private fun processTileData(td: Map<String, MutableList<FloatArray>>): TerrainObservation =
        TerrainObservation.fromDetections(td, semantic = detectTileDetector?.isSemanticTerrain == true)


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


    /**
     * Per-frame gameplay entry point.
     *
     * @param state classifier answer that caused the caller to invoke this method ("match" or
     *        "unknown"). A classifier answer is never proof of gameplay on its own.
     * @param capturedAtMs capture timestamp of [frame] (`snapshot.capturedAtMs`), used for the
     *        freshness and two-distinct-frames checks. Required: there is no fabricated default.
     * @param onStateChange reports the screen that this frame actually shows, so the engine can
     *        leave "match" when the player is not really detected.
     */
    fun main(
        frame: Mat,
        brawler: String,
        state: String?,
        capturedAtMs: Long,
        onStateChange: (String) -> Unit,
    ) {
        val issuingThread = Thread.currentThread()
        val diagnosticLoopStarted = PipelineTimingTelemetry.nowMs()
        PipelineTimingTelemetry.age(Stage.FRAME_AGE_START, capturedAtMs)
        try {
        classificationGeneration = PhoneVisualCues.chaosObservationGeneration()
        classificationCapturedAtMs = capturedAtMs
        val frameGeneration = synchronized(this) {
            val geometry = InputCoordinates.geometryGeneration
            if (gameplayGeometry != geometry) {
                resetCombat()
                gameplayEvidence.reset()
                lastTerrain = TerrainObservation()
                timeSinceWallsChecked = 0.0
                gameplayGeometry = geometry
            }
            combatGeneration
        }
        this.frame = frame
        val currentTime = nowSec()
        val detectStarted = monotonicNowMs()
        var data: MutableMap<String, MutableList<FloatArray>> = getMainData(frame)
        val detectMs = monotonicNowMs() - detectStarted
        DiagnosticLog.sample("gameplay.pipeline", "detect_ms=$detectMs")

        // Observe raw detections before the missing-player path discards them.
        DiagnosticLog.event("detection.player_presence", "present=${!data["player"].isNullOrEmpty()}")
        DiagnosticLog.sample("detection.raw", "player=${data["player"]?.size ?: 0} enemy=${data["enemy"]?.size ?: 0} teammate=${data["teammate"]?.size ?: 0} state=$state")
        data = validateGameData(data) ?: HashMap()
        trackNoDetections(data)

        val playerBoxes = data["player"]?.size ?: 0
        val boxValid = GameplayEvidenceGate.validPlayerBox(data["player"]?.firstOrNull(), frame.cols(), frame.rows())
        if (playerBoxes > 0) timeSincePlayerLastFound = currentTime

        val nowMs = monotonicNowMs()
        // "unknown" may only continue observation when the current frame itself is not a known menu
        // screen; a known menu is positive evidence that gameplay is not running.
        var reportedState: String? = null
        val current = if (InferenceTuner.reuseMenuVerdict(lastClassifyCostMs, isMenuScreen(lastClassified),
                capturedAtMs - lastClassifiedFrameMs, classifyReuses)) {
            classifyReuses++
            lastClassified
        } else {
            val classifyStarted = monotonicNowMs()
            classifyFrame(frame).also {
                lastClassifyCostMs = monotonicNowMs() - classifyStarted
                lastClassified = it; lastClassifiedFrameMs = capturedAtMs; classifyReuses = 0
            }
        }
        reportedState = current
        val statePermitted = (state == "match" || state == "unknown") && !isMenuScreen(current)

        val evidence = if (statePermitted) {
            gameplayEvidence.observe(statePermitted, playerBoxes, boxValid, capturedAtMs, nowMs)
        } else {
            gameplayEvidence.observeMenuScreen()
        }

        if (!evidence.admitted) {
            PipelineTimingTelemetry.interruption(evidence.reason.name)
            resetCombat()
            // No positive player evidence: no gameplay input at all. Release the joystick instead of
            // keeping a stale vector alive, and never dismiss an unrecognized screen blindly.
            windowController.releaseMovement()
            lastMovement = ""
            val runtimeState = if (state == "unknown") RuntimeState.UNKNOWN else RuntimeState.MATCH
            val phase = if (evidence.reason == GameEvidenceReason.PLAYER_MISSING ||
                evidence.reason == GameEvidenceReason.PLAYER_AMBIGUOUS ||
                evidence.reason == GameEvidenceReason.PLAYER_BOX_INVALID
            ) VerificationPhase.AWAIT_PLAYER else VerificationPhase.VERIFY_STATE
            BotStatus.publish(runtimeState, phase, verified = false)
            DiagnosticLog.event("gameplay.admission", "reason=${evidence.reason.name} phase=${phase.name}")
            maybeReportObservedState(if (statePermitted) "unknown" else reportedState, nowMs, onStateChange)
            return
        }
        maybeReportObservedState("match", nowMs, onStateChange)
        // Only admitted in-match frames count: loading screens and menus load the CPU differently.
        adaptEntityTier(detectMs)
        if (closed || Thread.currentThread().isInterrupted || !inputAllowed()) {
            PipelineTimingTelemetry.interruption("INPUT_DISABLED")
            resetCombat()
            gameplayEvidence.reset()
            windowController.releaseMovement()
            return
        }

        // Denied/loading frames need no wall inference. Admitted planning still uses current walls.
        val wallsStarted = monotonicNowMs()
        val terrainDue = currentTime - timeSinceWallsChecked > InferenceTuner.terrainIntervalSec(wallsTresholdSec, lastTerrainCostMs)
        val expectedTerrainMs = maxOf(lastTerrainCostMs, detectTileDetector?.tunedMedianMs ?: 0L)
        val deferTerrain = terrainDue &&
            InferenceTuner.deferTerrain(attackControlSearchPending, expectedTerrainMs, terrainDeferrals)
        if (deferTerrain) terrainDeferrals++
        if (terrainDue && !deferTerrain) {
            terrainDeferrals = 0
            val td = getTileData(frame, data["player"])
            lastTerrain = processTileData(td)
            timeSinceWallsChecked = currentTime
            lastTerrainCostMs = monotonicNowMs() - wallsStarted
        }
        lastTerrain.populate(data)
        DiagnosticLog.sample("gameplay.walls", "duration_ms=${monotonicNowMs()-wallsStarted}")

        if (currentTime - timeSinceHyperchargeChecked > hyperchargeTresholdSec) {
            isHyperchargeReady = checkIfHyperchargeReady(frame)
            timeSinceHyperchargeChecked = currentTime
        }
        if (currentTime - timeSinceGadgetChecked > gadgetTresholdSec) {
            isGadgetReady = checkIfGadgetReady(frame)
            timeSinceGadgetChecked = currentTime
        }
        if (currentTime - timeSinceSuperChecked > superTresholdSec) {
            isSuperReady = checkIfSuperReady(frame, capturedAtMs)
            timeSinceSuperChecked = currentTime
        }

        onBrawlerChanged(currentBrawler, brawler)
        currentBrawler = brawler
        val baseHold = brawlersInfo[brawler]?.holdAttack ?: 0.0
        val extraHold = secondsToHoldAttackAfterReachingMax
        val configuredHold = if (baseHold.isFinite() && baseHold > 0.0 && extraHold.isFinite())
            baseHold + maxOf(0.0, extraHold) else 3.0
        observedHoldLimitSeconds = if (configuredHold.isFinite() && configuredHold > 0.0)
            configuredHold.coerceAtMost(30.0) else 3.0
        for (key in listOf("enemy", "teammate")) {
            data[key] = (data[key] ?: emptyList()).filter {
                GameplayEvidenceGate.validPlayerBox(it, frame.cols(), frame.rows())
            }.toMutableList()
        }
        // Calibration starts only inside this already-admitted gameplay path. Saved coordinates
        // remove repeated matching, but never replace current gameplay/foreground/input authority.
        val calibrationLease = SavedAttackButtonCalibration.begin(frame.cols(), frame.rows(), gameplayGeometry)
        val savedAttackPoint = SavedAttackButtonCalibration.saved(calibrationLease)
        val diagnosticControlStarted = PipelineTimingTelemetry.nowMs()
        val attackPoint = try {
            if (savedAttackPoint != null) {
                AttackButtonDetector.recordSavedCalibration()
                savedAttackPoint
            } else locateAttackButton(frame)
        } catch (e: Exception) {
            DiagnosticLog.sample("combat.attack_detection_failed", "type=${e.javaClass.simpleName}")
            DiagnosticLog.failure("combat", "attack_detection", "detector_exception", e)
            null
        } finally {
            PipelineTimingTelemetry.elapsed(Stage.CONTROL_RECOGNITION, diagnosticControlStarted)
        }
        // Observation only: the detector publishes its own per-stage trace on this thread and clears
        // it before every detection, so a trace read here belongs to the call above. No decision,
        // timestamp or gate reads these values.
        recordAttack(runCatching { AttackButtonDetector.lastTrace() }.getOrNull())
        // Learning needs the control on two distinct fresh captures. On a slow device consecutive
        // loops are 1.5-2.5 s apart and a single unclear frame in between restarted the pair, so
        // the control was observed many times but never learned. After a first hit the next
        // capture is therefore checked right away instead of one whole loop later.
        val confirmation = if (savedAttackPoint == null && attackPoint != null)
            confirmAttackControl(capturedAtMs, frame.cols(), frame.rows()) else null
        synchronized(this) {
            if (frameGeneration != combatGeneration || closed || issuingThread.isInterrupted || !inputAllowed() ||
                gameplayGeometry != InputCoordinates.geometryGeneration) return
            // A reset/STOP/geometry change during matching invalidates this entire learning lease.
            if (!SavedAttackButtonCalibration.current(calibrationLease)) {
                attackButtonEvidence.reset()
                basicAttackEligible = false
                releaseAttackInput("attack_calibration_revoked")
                return
            }
            attackControlSearchPending = savedAttackPoint == null
            if (savedAttackPoint == null) {
                val learned = SavedAttackButtonCalibration.observeTemplate(
                    calibrationLease, attackPoint, capturedAtMs, monotonicNowMs(),
                )
                val confirmed = if (learned == null && confirmation != null) SavedAttackButtonCalibration.observeTemplate(
                    calibrationLease, confirmation.first, confirmation.second, monotonicNowMs(),
                ) else null
                DiagnosticLog.event("attack.calibration",
                    "status=${if (learned != null || confirmed != null) "learned_persistence_pending" else "awaiting_template_pair"}")
            }
            activeAttackCalibrationRevision = calibrationLease.revision
            // The same two distinct fresh captures feed the existing temporal button gate. The
            // persisted record must ALSO be present: a rejected calibration cannot authorize a tap.
            val observedButton = attackButtonEvidence.observe(attackPoint, capturedAtMs, monotonicNowMs(), gameplayGeometry)
            val button = observedButton.takeIf { SavedAttackButtonCalibration.saved(calibrationLease) != null }
            if (button == null) releaseAttackInput("attack_button_unverified")
            basicAttackEligible = button != null
            activeGameplayFrameMs = capturedAtMs
            activeGameplayTarget = !data["enemy"].isNullOrEmpty() || !data["teammate"].isNullOrEmpty()
            // Bounded observer-only labels from this frame; no pixels, coordinates or authority input.
            runCatching {
                DiagnosticLog.event("attack.evidence",
                    "pixels=${AttackButtonDetector.lastTrace()?.outcome ?: AttackRecognitionOutcome.NOT_ANALYZED} " +
                        "observation=${attackButtonEvidence.lastObservation()} accepted=${button != null}")
            }
        }
        // Strategy stage: interpretation only. No callback in the context may dispatch input, so the
        // whole tick is decided here and applied as one validated batch below.
        val frameScript = pylaScript
        val diagnosticScriptStarted = PipelineTimingTelemetry.nowMs()
        val outcome = try { runPlaystyle(brawler, data, frame) } finally {
            PipelineTimingTelemetry.elapsed(Stage.PLAYSTYLE, diagnosticScriptStarted)
        }
        PipelineTimingTelemetry.age(Stage.FRAME_AGE_DECISION, capturedAtMs)
        synchronized(this) {
            if (frameGeneration != combatGeneration || closed || issuingThread.isInterrupted || !inputAllowed() ||
                frameScript !== pylaScript || gameplayGeometry != InputCoordinates.geometryGeneration || !gameplayFrameCurrent()) {
                discardIntentsAndRelease("apply_revoked")
                PipelineTimingTelemetry.interruption("APPLY_REVOKED")
                resetCombat()
                gameplayEvidence.reset()
                windowController.releaseMovement()
                BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_STATE, false)
                maybeReportObservedState("unknown", monotonicNowMs(), onStateChange)
                return
            }
            try {
                // Request boundary before guarded queuing, not Android dispatch/callback time.
                PipelineTimingTelemetry.age(Stage.FRAME_AGE_DISPATCH, capturedAtMs)
                applyPlaystyleOutcome(outcome, frameGeneration, issuingThread)
            } catch (e: Exception) {
                // A throwing input driver or callback must never leave the first queued action live;
                // rotate every token and release. Only the generation that is still current may be
                // reset, so a stale in-flight apply cannot clear a newer owner's epoch.
                discardIntentsAndRelease("apply_exception")
                PipelineTimingTelemetry.interruption("APPLY_EXCEPTION")
                if (frameGeneration == combatGeneration && !closed) resetCombat()
                DiagnosticLog.event("playstyle.apply_failed", "type=${e.javaClass.simpleName}")
                DiagnosticLog.failure("combat", "apply_intents", "apply_exception", e)
            }
            // Guard-only deadman: releases a lapsed hold, never starts a new attack.
            startAttackPulse(brawler, issuingThread)
        }
        } finally {
            PipelineTimingTelemetry.elapsed(Stage.LOOP_TOTAL, diagnosticLoopStarted)
        }
    }

    /** Rate-limited hand-off of the actually observed screen to the engine. */
    private fun maybeReportObservedState(
        observed: String?,
        nowMs: Long,
        onStateChange: (String) -> Unit,
    ) {
        if (observed == null) return
        // Every observation carries a different capture authority. Suppressing repeated Match or
        // Unknown callbacks can leave the engine at a stale menu result or hide fresh player loss.
        lastStateReportMs = nowMs
        lastReportedState = observed
        onStateChange(observed)
    }

    private var lastReportedState: String? = null
    private var lastStateReportMs: Long = 0L

    @Volatile private var activeGameplayFrameMs: Long = 0L
    @Volatile private var activeGameplayTarget: Boolean = false
    @Volatile private var basicAttackEligible: Boolean = false
    private fun combatTargetCurrent(): Boolean = !closed && !Thread.currentThread().isInterrupted &&
        inputAllowed() && activeGameplayTarget && gameplayFrameCurrent()
    private fun gameplayFrameCurrent(): Boolean {
        val now = monotonicNowMs()
        return activeGameplayFrameMs > 0L && activeGameplayFrameMs <= now &&
            now - activeGameplayFrameMs <= MAX_EVIDENCE_FRAME_AGE_MS
    }

    // ---------- guarded attack dispatch ----------
    //
    // The selected `.pyla` result is the only owner of attack decisions: a script tap or hold is
    // buffered while it runs and dispatched here through one guarded native call per intent. The
    // engine no longer decides attacks on its own; [startAttackPulse] is a release-only deadman.
    //
    // [combatGeneration] is a volatile authority token. Every dispatched attack command captures the
    // generation current at issue time; a guard sampled later (possibly on a different thread) fails
    // closed as soon as the generation moved, so target loss / pause / stop cannot revive a queued
    // old DOWN. It advances on every [resetCombat] and [revokeCombat] even when nothing is held.
    // Per-channel demand tokens ([attackDemandToken], [abilityDemandToken]) additionally revoke a
    // queued finite tap once a later decision stops requesting it.

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var combatGeneration: Long = 0L

    /** True between a dispatched attack DOWN and its matching UP. Mirrors the policy's own state. */
    @Volatile
    private var attackButtonDown: Boolean = false
    @Volatile private var attackHeldSinceSec: Double = 0.0

    /** Legacy cadence state retained for compatibility; strategy intents own the decision. */
    private val attackPolicy = RepeatedAttackPolicy(MAX_EVIDENCE_FRAME_AGE_MS, requireNewCapture = false)
    private val attackButtonEvidence = AttackButtonEvidenceGate(MAX_EVIDENCE_FRAME_AGE_MS)
    @Volatile private var activeAttackCalibrationRevision = -1L
    internal var calibrationRevisionNow: () -> Long = SavedAttackButtonCalibration::revision
    internal var resetAttackCalibrationObservations: () -> Unit = SavedAttackButtonCalibration::resetObservations
    internal var locateAttackButton: (Mat) -> AttackButtonPoint? = AttackButtonDetector::detectForCalibration

    /**
     * Second observation for attack-control learning, taken from the newest capture. Returns the
     * recognised control and its capture time, or null when there is no newer same-size frame,
     * the frame is not plain gameplay any more, or the control is not recognised in it. It only
     * feeds the learning pair; it grants no input authority.
     */
    private fun confirmAttackControl(firstCapturedAtMs: Long, width: Int, height: Int): Pair<AttackButtonPoint, Long>? {
        return try {
            val next = windowController.screenshot()
            if (next.capturedAtMs <= firstCapturedAtMs || next.width != width || next.height != height) return null
            val mat = PylaUtils.frameToMat(next)
            try {
                if (isMenuScreen(StateFinder.getState(mat))) return null
                locateAttackButton(mat)?.let { it to next.capturedAtMs }
            } finally { mat.release() }
        } catch (t: Exception) {
            DiagnosticLog.failure("combat", "attack_confirmation", "threw", t)
            null
        }
    }

    /**
     * Adapter from the attack detector's immutable per-call trace to the fixed telemetry schema.
     * Observation only: a missing or unreadable trace degrades to `null` (rendered as absent) and
     * never changes what the attack stage decides.
     */
    private fun attackPhasesOf(trace: AttackRecognitionTrace?): PerceptionPerformanceTelemetry.AttackPhases? {
        if (trace == null) return null
        return PerceptionPerformanceTelemetry.AttackPhases(
            opaqueNanos = trace.opaqueNanos,
            translucentNanos = trace.translucentNanos,
            coarseNanos = trace.coarseNanos,
            refineNanos = trace.refineNanos,
            coarseCandidates = trace.coarseCandidates,
            refinementSeeds = trace.refinementSeeds,
            exhausted = trace.exhausted,
        )
    }

    /**
     * Fixed-schema attack-recognition diagnostic for this target, which has no separate
     * `gameplay.frame_timing` row: one rate-limited sample on the existing pipeline channel.
     * Content-free by construction (durations/counters only), never a decision input.
     */
    private fun recordAttack(trace: AttackRecognitionTrace?) {
        DiagnosticLog.sample("gameplay.pipeline", "attack=${PerceptionPerformanceTelemetry.attackDetail(attackPhasesOf(trace))}")
    }

    /**
     * Terminal stop: no further attack input may ever be produced and anything held is released.
     * Idempotent.
     */
    @Synchronized fun revokeCombat() {
        closed = true
        resetCombat()
    }

    /**
     * Reversible observation denial (player lost, menu screen, stale frame, pause). Releases held
     * input and revokes queued commands by advancing the authority generation, but keeps the object
     * usable for the next admitted frame.
     */
    @Synchronized fun resetCombat() {
        superEvidence = null
        // STOP/menu/player-loss ends a pending pair and fences old callbacks, not durable calibration.
        resetAttackCalibrationObservations()
        activeAttackCalibrationRevision = -1L
        attackButtonEvidence.reset()
        basicAttackEligible = false
        activeGameplayFrameMs = 0L
        activeGameplayTarget = false
        combatGeneration++
        // Old menu positives must not survive an admission/reset: readiness is re-observed, never
        // carried across a reset epoch.
        isGadgetReady = false
        isHyperchargeReady = false
        isSuperReady = false
        // Revoke script authority too: buffered intents are dropped at the next interpretation start
        // and any already-queued tap/DOWN is fenced off by the advanced demand/hold tokens.
        attackDemandToken++
        abilityDemandToken++
        holdIntentToken++
        holdIntentLive = false
        clearHoldLease()
        attackPulse?.let { removeCombatPulse(it) }
        attackPulse = null
        continuousPlanner.reset()
        sceneProgress.reset()
        releaseAttackInput(reason = "reset")
        windowController.releaseMovement()
    }

    /** One outstanding task; no catch-up burst and no new thread for each frame. */
    @Synchronized private fun startAttackPulse(brawler: String, issuingThread: Thread) {
        if (pulseBrawler != brawler && attackButtonDown) releaseAttackInput("brawler_changed")
        pulseBrawler = brawler
        if (attackPulse != null) return
        val generation = combatGeneration
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this) {
                if (attackPulse !== task || generation != combatGeneration) return@synchronized
                try {
                    if (!attackCommandGuard(generation, activeGameplayFrameMs, false, issuingThread)()) {
                        resetCombat()
                        return@synchronized
                    }
                    guardHeldAttack(issuingThread)
                    if (attackPulse === task) postCombatPulse(task, 100L)
                } catch (e: Exception) {
                    resetCombat()
                    DiagnosticLog.event("combat.pulse_failed", "type=${e.javaClass.simpleName}")
                    DiagnosticLog.failure("combat", "held_pulse", "pulse_exception", e)
                }
            }
        }
        attackPulse = task
        task.run()
    }

    /**
     * Guard for an already-dispatched attack command, re-evaluated at native dispatch time.
     *
     * It never trusts the immutable evidence captured when the command was issued alone: authority,
     * closed state and the *live* eligibility of the current frame are all re-checked here, and a
     * finite TAP additionally pins the capture timestamp it was issued from so newer frames cannot
     * rejuvenate a stale queued tap. A held charge is guarded by live eligibility plus the original
     * generation, which is what lets an ordinary run of fresh consecutive frames keep the charge
     * alive.
     */
    private fun attackCommandGuard(
        generation: Long,
        capturedAtMs: Long,
        finite: Boolean,
        thread: Thread,
        buttonRevision: Long? = null,
    ): () -> Boolean {
        // Capture, rather than reread, the calibration epoch of THIS queued command. Reset and
        // relearning the same coordinate must never revive a previously queued gesture.
        val calibrationRevision = activeAttackCalibrationRevision
        return guard@{
        if (closed) false
        // The guard may be sampled on the input thread, where Thread.currentThread() is not the
        // engine thread; check the issuing thread's own interrupt flag instead.
        else if (thread.isInterrupted) false
        else if (combatGeneration != generation) false
        else if (gameplayGeometry != InputCoordinates.geometryGeneration) false
        else if (!inputAllowed()) false
        else if (!gameplayFrameCurrent()) false
        else if (buttonRevision != null && (!basicAttackEligible ||
            activeAttackCalibrationRevision != calibrationRevision ||
            calibrationRevisionNow() != calibrationRevision ||
            attackButtonEvidence.current(monotonicNowMs(), InputCoordinates.geometryGeneration)?.revision != buttonRevision)) false
        else if (finite && (capturedAtMs <= 0L || capturedAtMs > monotonicNowMs() ||
            monotonicNowMs() - capturedAtMs > MAX_EVIDENCE_FRAME_AGE_MS)) false
        else true
        }
    }

    /**
     * Release a held attack button exactly once. Releases are deliberately unguarded - a DOWN that
     * was legitimately dispatched must always be matched by its UP, otherwise the button stays
     * stuck down.
     */
    private fun releaseAttackInput(reason: String) {
        if (holdIntentLive) holdIntentToken++
        holdIntentLive = false
        clearHoldLease()
        if (!attackButtonDown) {
            attackButtonDown = false
            return
        }
        attackButtonDown = false
        attackHeldSinceSec = 0.0
        windowController.press("attack", touchUp = true, touchDown = false)
        // Drops any in-flight charge; the cadence clock is deliberately NOT reset, so an immediate
        // re-acquisition cannot hand out a free burst.
        attackPolicy.revoke()
        // The script's own hold epoch is stale now that the charge really ended.
        clearScriptHoldEpoch()
        DiagnosticLog.event("combat.attack_release", "reason=$reason")
    }

    /**
     * Deadman for an already held charge. This never decides a new attack: every attack choice comes
     * from the validated strategy decision. It only keeps the held pointer honest between frames, so
     * a lapsed frame, host denial, interruption or a strategy that stopped holding releases the DOWN
     * instead of leaving the button pressed.
     */
    @Synchronized private fun guardHeldAttack(issuingThread: Thread) {
        if (!attackButtonDown) return
        if (closed || issuingThread.isInterrupted || !inputAllowed() || !gameplayFrameCurrent()) {
            endHold("deadman_authority")
            return
        }
        if (!holdIntentLive || !holdLeaseCurrent()) {
            endHold("hold_intent_expired")
            return
        }
        val button = attackButtonEvidence.current(monotonicNowMs(), InputCoordinates.geometryGeneration)
        if (button == null || !basicAttackEligible) {
            // Revoking the DOWN must also invalidate the hold intent: leaving holdIntentLive true
            // would make the next genuine HOLD look "continued" and never re-dispatch a DOWN.
            endHold("attack_button_unverified")
            DiagnosticLog.sample("combat.attack_idle", "denial=ATTACK_BUTTON_UNVERIFIED mode=strategy_hold")
        }
    }

    /** Whole outcome of one strategy tick. */
    private sealed interface PlaystyleOutcome {
        /** No interpreter installed (or no player box): nothing was decided, nothing may stay held. */
        object NoScript : PlaystyleOutcome

        data class Decided(
            val movement: PlaystyleMovement,
            val intents: List<PlaystyleIntent>,
            /** Fully isolated post-run persistence; committed only under the apply monitor. */
            val persistence: PyDict,
            val holdEpochRevision: Long,
            val randomMovementCursor: Int,
        ) : PlaystyleOutcome

        /** Fixed telemetry status plus a fixed internal reason token. */
        data class Failed(val status: String, val reason: String) : PlaystyleOutcome
    }

    /**
     * Runs the selected `.pyla` once against an isolated snapshot of the current frame.
     *
     * Interpretation has no input side effects: `attack()`/`use_*()` only buffer intents, movement is
     * the returned value, and `get_random_movement()` only proposes a bounded candidate. Persistence
     * is staged in a deep copy and committed only for a whole, valid decision, so an error, budget
     * stop, cancellation, missing or malformed output can never leak state or authorize input.
     */
    private fun runPlaystyle(brawler: String, data: Map<String, MutableList<FloatArray>>, frame: Mat): PlaystyleOutcome {
        val script = pylaScript ?: return PlaystyleOutcome.NoScript
        val playerBox = data["player"]?.firstOrNull() ?: return PlaystyleOutcome.NoScript
        val generation = combatGeneration
        val issuingThread = Thread.currentThread()
        val cancelled = {
            closed || issuingThread.isInterrupted || combatGeneration != generation || !inputAllowed()
        }
        // Snapshot the shared mutable inputs under the same monitor that owns them: a reset/release
        // on another thread mutates persistent_data (and may rotate the hold epoch), so the copy is
        // never built from a map that is being iterated concurrently.
        val snapshot = try {
            synchronized(this) {
                Triple(
                    PyDict(LinkedHashMap<Any, Any>().apply {
                        for ((key, value) in persistentDataPy.map) put(key, deepCopyPlaystyleValue(value))
                    }),
                    IntArray(1) { randomMovementIndex },
                    scriptHoldEpochRevision,
                )
            }
        } catch (t: Throwable) {
            DiagnosticLog.failure("playstyle", "snapshot", "persistence_snapshot_exception", t)
            reportPlaystyleTick(PlaystyleTelemetry.INVALID_OUTPUT, script, 0L, 0L, PERSISTENCE_INVALID)
            return PlaystyleOutcome.Failed(PlaystyleTelemetry.INVALID_OUTPUT, PERSISTENCE_INVALID)
        }
        val staged = snapshot.first
        val cursor = snapshot.second
        val epochRevision = snapshot.third
        // One collector per tick, passed explicitly: it never outlives this run and no reset/discard
        // path can retain a collector (or its old frame) across ticks.
        val collector = PlaystyleIntentCollector()
        val context = try {
            buildContext(brawler, playerBox, data, frame, staged, collector, cursor)
        } catch (t: Throwable) {
            DiagnosticLog.failure("playstyle", "context", "context_build_exception", t)
            // A context snapshot that cannot be isolated (e.g. the cached catalog) must never be
            // handed to the interpreter: fail closed before any input is produced.
            reportPlaystyleTick(PlaystyleTelemetry.INVALID_OUTPUT, script, 0L, 0L, PERSISTENCE_INVALID)
            return PlaystyleOutcome.Failed(PlaystyleTelemetry.INVALID_OUTPUT, PERSISTENCE_INVALID)
        }
        val result = try {
            script.runBounded(context, cancelled)
        } catch (t: Throwable) {
            DiagnosticLog.failure("playstyle", "execute", "uncaught_execution_exception", t)
            // runBounded already folds script failures; anything escaping it is still a failed tick.
            PylaRunResult(PylaRunStatus.RUNTIME_ERROR, null, 0L, 0L)
        }
        val elapsedMs = result.elapsedNanos / 1_000_000L
        val runtimeStatus = when (result.status) {
            PylaRunStatus.SUCCESS -> PlaystyleTelemetry.SUCCESS
            PylaRunStatus.RUNTIME_ERROR -> PlaystyleTelemetry.RUNTIME_ERROR
            PylaRunStatus.BUDGET_EXCEEDED -> PlaystyleTelemetry.BUDGET_EXCEEDED
            PylaRunStatus.CANCELLED -> PlaystyleTelemetry.CANCELLED
        }
        if (result.status != PylaRunStatus.SUCCESS) {
            reportPlaystyleTick(runtimeStatus, script, elapsedMs, result.steps, result.status.name)
            return PlaystyleOutcome.Failed(runtimeStatus, result.status.name)
        }
        // A script can build a cycle or store a callable in the staged graph; re-validate the whole
        // post-run graph before it is even considered for commit.
        val validated = try {
            deepCopyPlaystyleValue(staged) as PyDict
        } catch (t: Throwable) {
            DiagnosticLog.failure("playstyle", "persistence_validate", "persistence_validation_exception", t)
            reportPlaystyleTick(PlaystyleTelemetry.INVALID_OUTPUT, script, elapsedMs, result.steps, PERSISTENCE_INVALID)
            return PlaystyleOutcome.Failed(PlaystyleTelemetry.INVALID_OUTPUT, PERSISTENCE_INVALID)
        }
        return when (val decision = PlaystyleDecision.decide(movementOutput(result.movement), collector.finish())) {
            is PlaystyleDecisionResult.Rejected -> {
                reportPlaystyleTick(PlaystyleTelemetry.INVALID_OUTPUT, script, elapsedMs, result.steps, decision.reason)
                PlaystyleOutcome.Failed(PlaystyleTelemetry.INVALID_OUTPUT, decision.reason)
            }
            is PlaystyleDecisionResult.Accepted -> {
                reportPlaystyleTick(PlaystyleTelemetry.SUCCESS, script, elapsedMs, result.steps, null)
                PlaystyleOutcome.Decided(decision.movement, decision.intents, validated, epochRevision, cursor[0])
            }
        }
    }

    /** Fixed-schema, content-free telemetry of one strategy tick. */
    private fun reportPlaystyleTick(status: String, script: PylaScript, elapsedMs: Long, steps: Long, reason: String?) {
        DiagnosticLog.event(
            PlaystyleTelemetry.CHANNEL,
            PlaystyleTelemetry.detail(status, script.meta.filename, elapsedMs, steps, reason),
        )
    }

    private fun movementOutput(raw: Any?): PlaystyleMovementOutput = when {
        raw == null -> PlaystyleMovementOutput.Missing
        raw === PyNone -> PlaystyleMovementOutput.None
        else -> pyToPair(raw)?.let { PlaystyleMovementOutput.Vector(it.first, it.second) }
            ?: PlaystyleMovementOutput.Invalid
    }


    private fun isMenuScreen(state: String?): Boolean =
        state != null && state != "unknown" && state != "match"

    /**
     * Applies one completed strategy tick. Every branch here either dispatches a whole validated
     * decision through the existing guarded calls or releases everything the strategy may hold.
     */
    private fun applyPlaystyleOutcome(outcome: PlaystyleOutcome, generation: Long, issuingThread: Thread) {
        when (outcome) {
            is PlaystyleOutcome.NoScript -> {
                discardIntentsAndRelease("no_strategy")
                windowController.releaseMovement()
                BotStatus.publish(RuntimeState.MATCH, VerificationPhase.HOLD, verified = false)
            }
            is PlaystyleOutcome.Failed -> {
                discardIntentsAndRelease("strategy_" + outcome.reason)
                windowController.releaseMovement()
                lastMovement = ""
                BotStatus.publish(RuntimeState.MATCH, VerificationPhase.VERIFY_STATE, verified = false)
                DiagnosticLog.event("playstyle.discarded", "status=${outcome.status} reason=${outcome.reason}")
            }
            is PlaystyleOutcome.Decided -> applyPlaystyleDecision(outcome, generation, issuingThread)
        }
    }

    private fun applyPlaystyleDecision(decision: PlaystyleOutcome.Decided, generation: Long, issuingThread: Thread) {
        val intents = decision.intents.toMutableList()
        // Shipped charge scripts request DOWN once, then communicate their waiting phase through
        // the same persistent timestamp. Only renew an existing same-module bounded lease; never
        // infer a new DOWN, continue after a failed tick, or inherit another script's charge.
        if (intents.none { it is PlaystyleIntent.Tap || it is PlaystyleIntent.Hold || it is PlaystyleIntent.Release } &&
            canContinueScriptHold(decision.persistence)) intents.add(PlaystyleIntent.Hold)
        val attackRequested = intents.any { it is PlaystyleIntent.Tap || it is PlaystyleIntent.Hold || it is PlaystyleIntent.Release }
        // Per-decision finite tokens: advancing on EVERY accepted decision guarantees an older
        // finite tap/ability cannot survive a newer decision on the same channel, even when the
        // button revision itself did not change.
        attackDemandToken++
        abilityDemandToken++
        val attackToken = attackDemandToken
        val abilityToken = abilityDemandToken
        val button = if (attackRequested) {
            attackButtonEvidence.current(monotonicNowMs(), InputCoordinates.geometryGeneration)
                .takeIf { calibrationRevisionNow() == activeAttackCalibrationRevision }
        } else null

        // A strategy that stopped asking for the charge must not keep the button down.
        if (!attackRequested && holdIntentLive) endHold("hold_not_requested")

        // Atomic commit of the staged tick: the caller rechecked generation, closed state, issuing
        // thread, input authority, frame freshness and geometry under this monitor.
        commitPlaystylePersistence(decision.persistence, decision.holdEpochRevision)
        randomMovementIndex = decision.randomMovementCursor

        for (intent in intents) {
            when (intent) {
                is PlaystyleIntent.Ability -> {
                    if (!issueSpecialAbility(intent.key, abilityToken)) {
                        DiagnosticLog.event("gameplay.input_denied", "channel=ability reason=STRATEGY_GUARD name=${intent.key}")
                    }
                }
                PlaystyleIntent.Tap -> {
                    if (holdIntentLive) endHold("tap_transition")
                    if (button == null) {
                        DiagnosticLog.event("gameplay.input_denied", "channel=attack reason=ATTACK_BUTTON_UNVERIFIED form=tap")
                        continue
                    }
                    windowController.pressCombatAt(
                        button.point.x, button.point.y,
                        attackIntentGuard(generation, button, finite = true, issuingThread, attackToken),
                    )
                    BotStatus.action("Attack")
                    DiagnosticLog.event("playstyle.attack_requested", "form=tap")
                }
                PlaystyleIntent.Hold -> requestHold(button, generation, issuingThread, attackToken)
                PlaystyleIntent.Release -> endHold("playstyle_release")
            }
        }

        // Movement is the strategy's own decision, including an intentional still.
        when (val movement = decision.movement) {
            is PlaystyleMovement.Still -> doMovement(null)
            is PlaystyleMovement.Vector -> {
                // The movement guard keeps its own freshness/epoch check and WindowController owns
                // the reference->capture conversion (PlaystyleContract); do not pre-scale here or
                // the requested direction would be converted twice.
                windowController.moveGuarded(
                    movement.dx.toFloat(), movement.dy.toFloat(),
                    movementCommandGuard(generation, issuingThread),
                )
            }
        }
        val applied: Pair<Double, Double> = when (val movement = decision.movement) {
            is PlaystyleMovement.Still -> 0.0 to 0.0
            is PlaystyleMovement.Vector -> movement.dx to movement.dy
        }
        val previous = lastMovement as? Pair<*, *>
        if (previous == null || previous.first != applied.first || previous.second != applied.second) {
            lastMovementChangeTime = nowSec()
        }
        lastMovement = applied
        val still = decision.movement is PlaystyleMovement.Still
        BotStatus.publish(RuntimeState.MATCH, if (still) VerificationPhase.HOLD else VerificationPhase.ROAM, verified = true)
        DiagnosticLog.sample(
            "playstyle.decision",
            "movement=${if (still) "still" else "vector"} intents=${intents.size}",
        )
    }

    /**
     * Continuous charge request. The first HOLD of a charge advances [holdIntentToken]; later HOLD
     * frames keep it, so one fluid DOWN stays valid and no second DOWN is ever dispatched.
     */
    private fun requestHold(
        button: AttackButtonEvidenceGate.Evidence?,
        generation: Long,
        issuingThread: Thread,
        attackToken: Long,
    ) {
        if (button == null) {
            DiagnosticLog.event("gameplay.input_denied", "channel=attack reason=ATTACK_BUTTON_UNVERIFIED form=hold")
            // A denied first DOWN must clear the script's speculative charging latch too.
            endHold("attack_button_unverified")
            return
        }
        if (holdIntentLive) {
            if (!holdLeaseCurrent()) endHold("hold_lease_expired")
            else DiagnosticLog.event("playstyle.attack_requested", "form=hold continued=true")
            return // Preserve the UP boundary; a later fresh explicit DOWN may begin a new charge.
        }
        holdIntentToken++
        val holdToken = holdIntentToken
        holdOwnerScript = pylaScript
        holdOwnerBrawler = currentBrawler
        holdOwnerEpochSeconds = scriptHoldEpoch(persistentDataPy)
        holdButtonRevision = button.revision
        holdLeaseStartedAtMs = monotonicNowMs()
        val seconds = observedHoldLimitSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 3.0
        holdLeaseDurationMs = (seconds.coerceAtMost(30.0) * 1000.0).toLong().coerceAtLeast(1L)
        holdIntentLive = true
        attackHeldSinceSec = nowSec()
        attackButtonDown = true
        val heldCommandGuard = attackIntentGuard(generation, button, finite = false, issuingThread, attackToken)
        windowController.holdCombatAt(button.point.x, button.point.y, {
            holdIntentLive && holdIntentToken == holdToken && holdLeaseCurrent() && heldCommandGuard()
        })
        BotStatus.action("Charge attack")
        DiagnosticLog.event("playstyle.attack_requested", "form=hold")
    }

    /** A strategy attack command additionally pins the decision that requested it. */
    private fun attackIntentGuard(
        generation: Long,
        button: AttackButtonEvidenceGate.Evidence,
        finite: Boolean,
        issuingThread: Thread,
        attackToken: Long,
    ): () -> Boolean {
        val commandGuard = attackCommandGuard(generation, button.capturedAtMs, finite, issuingThread, button.revision)
        return {
            if (finite && attackDemandToken != attackToken) {
                DiagnosticLog.event("gameplay.input_denied", "channel=attack reason=STRATEGY_REVOKED")
                false
            } else commandGuard()
        }
    }

    /**
     * Same lifecycle/epoch/geometry/freshness contract as the attack guard, for the movement
     * channel: no button or capture-pinning applies, but a stale frame or a moved generation still
     * fails closed. Fixed denial tokens only.
     */
    private fun movementCommandGuard(generation: Long, thread: Thread): () -> Boolean = {
        val denial = when {
            closed -> "CLOSED"
            thread.isInterrupted -> "ISSUING_THREAD_INTERRUPTED"
            combatGeneration != generation -> "GENERATION_CHANGED"
            gameplayGeometry != InputCoordinates.geometryGeneration -> "GEOMETRY_CHANGED"
            !inputAllowed() -> "INPUT_DISABLED"
            !gameplayFrameCurrent() -> "FRAME_STALE"
            else -> null
        }
        PipelineTimingTelemetry.age(Stage.FRAME_AGE_GUARD, activeGameplayFrameMs)
        if (denial != null) {
            PipelineTimingTelemetry.interruption(denial)
            DiagnosticLog.event("gameplay.input_denied", "channel=movement reason=$denial")
        }
        denial == null
    }

    /** Ends a strategy charge: invalidate its queued DOWN, then release unconditionally. */
    @Synchronized private fun endHold(reason: String) {
        if (holdIntentLive) holdIntentToken++
        holdIntentLive = false
        clearHoldLease()
        releaseAttackInput(reason)
        // Explicit release dominates a same-tick speculative rehold even if the old DOWN never
        // reached Android: the next fresh script tick must begin with a genuinely clear latch.
        clearScriptHoldEpoch()
    }

    private fun scriptHoldEpoch(data: PyDict): Double? =
        (data.map["time_since_holding_attack"] as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it > 0.0 && it <= nowSec()
        }

    private fun canContinueScriptHold(staged: PyDict): Boolean =
        holdOwnerScript != null && holdLeaseCurrent() && holdOwnerEpochSeconds != null &&
            scriptHoldEpoch(staged) == holdOwnerEpochSeconds

    /** Absolute monotonic bound; fresh decisions never extend the original charge's deadline. */
    private fun holdLeaseCurrent(): Boolean {
        val at = monotonicNowMs()
        return holdIntentLive && holdOwnerScript === pylaScript && holdOwnerBrawler == currentBrawler &&
            holdLeaseStartedAtMs > 0L && at >= holdLeaseStartedAtMs &&
            at - holdLeaseStartedAtMs < holdLeaseDurationMs &&
            attackButtonEvidence.current(at, InputCoordinates.geometryGeneration)?.revision == holdButtonRevision
    }

    private fun clearHoldLease() {
        holdOwnerScript = null
        holdOwnerBrawler = null
        holdOwnerEpochSeconds = null
        holdButtonRevision = 0L
        holdLeaseStartedAtMs = 0L
        holdLeaseDurationMs = 0L
    }

    /**
     * Drops the buffered intents of a failed or revoked tick and fences off script commands that may
     * already be queued. Releases stay unconditional, so nothing can remain pressed.
     */
    @Synchronized private fun discardIntentsAndRelease(reason: String) {
        // The per-tick collector is local to runPlaystyle; discard only rotates demand tokens and
        // releases input, so no collector (or its old frame) can be retained here.
        attackDemandToken++
        abilityDemandToken++
        holdIntentToken++
        holdIntentLive = false
        releaseAttackInput(reason)
    }

    /** A brawler change ends the old charge and resets the script's stale hold epoch. */
    @Synchronized private fun onBrawlerChanged(previous: String?, brawler: String) {
        if (previous == null || previous == brawler) return
        endHold("brawler_changed")
        clearScriptHoldEpoch()
        randomMovementIndex = 0
    }

    /**
     * Commits one validated decision's staged persistence under the apply monitor.
     *
     * If a release/clear ran while the interpreter was working, that native clear owns the hold
     * epoch: the stale staged value is overwritten with `None` instead of resurrecting a hold the
     * engine already ended.
     */
    @Synchronized private fun commitPlaystylePersistence(staged: PyDict, epochRevision: Long) {
        persistentDataPy.map.clear()
        persistentDataPy.map.putAll(staged.map)
        if (scriptHoldEpochRevision != epochRevision) {
            persistentDataPy.map["time_since_holding_attack"] = PyNone
        }
    }

    /**
     * `time_since_holding_attack` is script-owned (`time.time()` seconds). Native code never writes a
     * native hold epoch into it; it only clears the key to `None` when a charge really ended, so a
     * later script cannot believe it is still charging.
     */
    private fun clearScriptHoldEpoch() {
        if (persistentDataPy.map["time_since_holding_attack"] === PyNone) return
        persistentDataPy.map["time_since_holding_attack"] = PyNone
        scriptHoldEpochRevision++
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
            DiagnosticLog.failure("playstyle", "catalog", "catalog_parse_exception", t)
            Log.w(TAG, "brawlers_info parse failed", t)
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
        PyList(box.mapTo(ArrayList<Any>(box.size)) { it.toDouble() as Any })

    private fun boxesToPy(boxes: List<FloatArray>): PyList =
        PyList(boxes.mapTo(ArrayList<Any>(boxes.size)) { boxToPy(it) as Any })

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

    private fun buildContext(
        brawler: String,
        playerBox: FloatArray,
        data: Map<String, MutableList<FloatArray>>,
        frame: Mat,
        persistent: PyDict,
        collector: PlaystyleIntentCollector,
        randomCursor: IntArray,
    ): Map<String, Any> {
        val ctx = HashMap<String, Any>()

        // Snapshot values only: the script sees copies, never a live detection list it could mutate.
        ctx["player_data"] = boxToPy(playerBox)
        ctx["enemy_data"] = boxesToPy(data["enemy"]?.toList() ?: emptyList())
        ctx["teammate_data"] = boxesToPy(data["teammate"]?.toList() ?: emptyList())
        ctx["walls"] = boxesToPy(data["wall"]?.toList() ?: emptyList())
        ctx["bushes"] = boxesToPy(data["bush"]?.toList() ?: emptyList())
        ctx["waters"] = boxesToPy(data["water"]?.toList() ?: emptyList())
        ctx["rope_fences"] = boxesToPy(data["rope_fence"]?.toList() ?: emptyList())
        ctx["out_of_bounds"] = boxesToPy(data["out_of_bounds"]?.toList() ?: emptyList())
        ctx["brawler"] = brawler
        ctx["current_brawler"] = currentBrawler ?: PyNone
        // Isolated copy of the cached catalog: a script that mutates or poisons what it reads can
        // never corrupt the shared cache used by the next tick.
        ctx["brawlers_info"] = deepCopyPlaystyleValue(brawlersInfoPy()) as PyDict
        ctx["is_gadget_ready"] = isGadgetReady
        ctx["is_hypercharge_ready"] = isHyperchargeReady
        ctx["is_super_ready"] = isSuperReady
        ctx["TILE_SIZE"] = tileSizePx
        ctx["JOYSTICK_RADIUS"] = PylaUtils.JOYSTICK_RADIUS.toLong()
        // Actual capture size; older builds hardcoded 1920x1080, which is wrong on wide captures.
        ctx["width"] = frame.cols().toLong()
        ctx["height"] = frame.rows().toLong()
        // Shared context contract (PC parity). Detection boxes, `width`/`height` and every input
        // coordinate are capture-space; a strategy movement vector is reference-space (the
        // 1920x1080 canvas the ratios are defined against). Strategies that need device-independent
        // geometry must build it from these ratios; `WindowController.moveGuarded` converts the
        // returned vector back to capture space with the very same numbers, so a vector of one
        // JOYSTICK_RADIUS is one joystick radius on every aspect ratio.
        val geometry = PlaystyleContract.geometry(frame.cols(), frame.rows())
        ctx["width_ratio"] = geometry?.widthRatio ?: 1.0
        ctx["height_ratio"] = geometry?.heightRatio ?: 1.0
        ctx["debug"] = verboseDebug
        ctx["seconds_to_hold_attack_after_reaching_max"] = secondsToHoldAttackAfterReachingMax
        // Staged deep copy: committed by runPlaystyle only for a whole, valid decision.
        ctx["persistent_data"] = persistent
        ctx["last_movement"] = when (val previous = lastMovement) {
            is Pair<*, *> -> pairAsPy(previous)
            is String -> previous
            else -> PyNone
        }
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
        // Intent-only script surface. `attack()`/`use_*()` buffer a request for this tick; nothing is
        // dispatched while the interpreter runs, so an error later in the same script cannot leave a
        // half-applied action behind. `attack(touch_up, touch_down)` keeps the documented semantics:
        // both true = tap, down only = hold, up only = release.
        ctx["attack"] = NativeFn("attack") { args, kwargs ->
            if (!collector.requestAttack(args, kwargs)) {
                DiagnosticLog.event("playstyle.intent_rejected", "reason=${collector.rejected ?: "UNKNOWN"}")
            }
            PyNone
        }
        ctx["use_hypercharge"] = NativeFn("use_hypercharge") { _, _ -> collector.requestAbility("hypercharge"); PyNone }
        ctx["use_super"] = NativeFn("use_super") { _, _ -> collector.requestAbility("super"); PyNone }
        ctx["use_gadget"] = NativeFn("use_gadget") { _, _ -> collector.requestAbility("gadget"); PyNone }
        // Bounded candidate from a fixed direction table, produced only when the script asks for it.
        // The cursor is staged per tick: a failed tick never advances the helper's future state.
        ctx["get_random_movement"] = NativeFn("get_random_movement") { _, _ ->
            pairToPy(
                PlaystyleRandomMovement.candidate(
                    randomCursor[0]++,
                    PylaUtils.JOYSTICK_RADIUS.toDouble(),
                ),
            )
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
            val distance = a.getOrNull(3)?.let { Py.toDouble(it) }
            isPathBlocked(box, move, walls, distance)
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
        // PC parity for `normalize_move(x, y, radius=JOYSTICK_RADIUS)`: returns a vector of exactly
        // `radius` (reference space) or `(0, 0)` for zero/non-finite input. A bad argument yields
        // `(0, 0)` instead of raising, so it can only ever produce a still decision.
        ctx["normalize_move"] = NativeFn("normalize_move") { a, kw ->
            val radiusArg = kw["radius"] ?: a.getOrNull(2)
            val radius = radiusArg?.takeIf { Py.isNumber(it) }?.let { Py.toDouble(it) }
                ?: PylaUtils.JOYSTICK_RADIUS.toDouble()
            val x = a.getOrNull(0)?.takeIf { Py.isNumber(it) }?.let { Py.toDouble(it) }
            val y = a.getOrNull(1)?.takeIf { Py.isNumber(it) }?.let { Py.toDouble(it) }
            val v = if (x == null || y == null) PlaystyleContract.Vector(0.0, 0.0)
            else PlaystyleContract.normalizeMove(x, y, radius)
            pairToPy(v.x to v.y)
        }

        return ctx
    }

    private fun pairAsPy(p: Pair<*, *>): PyTuple =
        PyTuple(listOf((p.first as Number).toDouble(), (p.second as Number).toDouble()))

    fun close() {
        // Terminal: no attack input may ever be produced after close, and anything held comes up.
        revokeCombat()
        frame = null
        detectMainInfo.close()
        detectTileDetector?.close()
        detectCenteredTileDetector?.close()
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
