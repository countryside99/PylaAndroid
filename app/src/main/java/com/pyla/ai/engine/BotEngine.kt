package com.pyla.ai.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.config.PylaConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BotEngine(
    val appContext: Context,
    var queueData: MutableList<MutableMap<String, Any>>,
) {
    companion object {
        private const val TAG = "PylaEngine"
        private const val CAPTURE_STARTUP_TIMEOUT_MS = 15_000L
        private const val GAME_REFOCUS_DELAY_MS = 8_000L
        private const val GAME_RELAUNCH_COOLDOWN_MS = 20_000L
        @Volatile var instance: BotEngine? = null

        fun saveBrawlerData(data: List<MutableMap<String, Any>>) {
            val arr = JSONArray()
            for (m in data) {
                val o = JSONObject()
                for ((k, v) in m) o.put(k, when (v) {
                    is Number -> v
                    is Boolean -> v
                    is String -> v
                    is List<*> -> JSONArray(v)
                    else -> v.toString()
                })
                arr.put(o)
            }
            File(PylaConfig.root(), "latest_brawler_data.json").writeText(arr.toString())
        }

        fun loadBrawlerData(): MutableList<MutableMap<String, Any>> {
            val f = File(PylaConfig.root(), "latest_brawler_data.json")
            if (!f.exists()) return mutableListOf()
            return try {
                val arr = JSONArray(f.readText())
                val out = mutableListOf<MutableMap<String, Any>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val m = HashMap<String, Any>()
                    for (k in o.keys()) m[k] = o.get(k)
                    out.add(m)
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "load queue: ${t.message}")
                mutableListOf()
            }
        }
    }

    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile var latestState: String? = null
        private set

    private lateinit var windowController: WindowController
    private lateinit var timeManagement: TimeManagement
    private lateinit var lobbyAutomator: LobbyAutomation
    private lateinit var play: Play
    private lateinit var stageManager: StageManager

    private lateinit var loopThread: HandlerThread
    private lateinit var stateThread: HandlerThread

    private var inCooldown = false
    private var cooldownStartMs = 0L
    private val cooldownDurationMs = 3 * 60 * 1000L
    private var startTimeMs = 0L

    private val gamePackage: String get() = try { GameLauncher.gamePackage() } catch (_: Throwable) { GameLauncher.DEFAULT_PACKAGE }
    private val runForMinutes: Int get() = try { PylaConfig.load("cfg/general_config.toml").getInt("run_for_minutes", 0) } catch (_: Throwable) { 0 }
    private var gameLostFocusSinceMs = 0L
    private var lastGameLaunchMs = 0L
    private var matchLoopMs = 0L
    private var matchLoopSamples = 0

    fun start() {
        stopRequested.set(false); paused.set(false)
        instance = this
        BotStatus.engineRunning = true
        BotStatus.lastError = ""
        BotStatus.inputConnected = com.pyla.ai.input.InputService.isConnected()
        BotStatus.queueSummary = queueData.joinToString(", ") { it["brawler"].toString() }

        PylaLog.p(TAG, "Auto-opening Brawl Stars ($gamePackage)")
        if (GameLauncher.launch(appContext, gamePackage)) {
            lastGameLaunchMs = System.currentTimeMillis()
        }
        loopThread = HandlerThread("pyla-bot-loop").apply { start() }
        Handler(loopThread.looper).post {

            try {
                bootstrapAndRun()
            } catch (t: Throwable) {
                PylaLog.e(TAG, "engine bootstrap failed", t)
                BotStatus.error("Engine start failed: ${t.message}")
                try { stop() } catch (_: Throwable) {}
            }
        }
    }

    private fun bootstrapAndRun() {
        val deadlineMs = System.currentTimeMillis() + CAPTURE_STARTUP_TIMEOUT_MS
        var capture = CaptureService.instance
        while (capture == null && !stopRequested.get()) {
            if (System.currentTimeMillis() > deadlineMs) {
                Log.e(TAG, "CaptureService did not come up within ${CAPTURE_STARTUP_TIMEOUT_MS / 1000}s")
                BotStatus.error("CaptureService not running (screen capture permission not granted?)")
                stop()
                return
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
            capture = CaptureService.instance
        }
        if (capture == null) return
        PylaLog.p(TAG, "CaptureService is up, initializing engine")

        windowController = WindowController(capture)
        timeManagement = TimeManagement()
        lobbyAutomator = LobbyAutomation(windowController)

        val prefs = appContext.getSharedPreferences("pyla", Context.MODE_PRIVATE)
        val playstyle = Playstyles.byId(prefs.getString("playstyle", null))
        PylaLog.p(TAG, "Selected playstyle: ${playstyle.displayName} (gamemodes=${playstyle.gamemodes})")

        val playstyleInfo = HashMap<String, Any>()
        playstyleInfo["name"] = "${playstyle.displayName} (Android)"
        playstyleInfo["gamemodes"] = playstyle.gamemodes
        playstyleInfo["brawlers"] = listOf("all")

        play = Play(
            windowController,
            mainInfoModelPath = "models/mainInGameModel.onnx",
            tileDetectorModelPath = "models/tileDetector.onnx",
            closeTileDetectorModelPath = "models/closeTileDetector.onnx",
            playstyle = playstyle,
        )
        play.antiIdleEnabled = prefs.getBoolean("anti_idle", false)
        PylaLog.p(TAG, "Anti-Idle: ${if (play.antiIdleEnabled) "ON" else "off"}")
        stageManager = StageManager(
            queueData, lobbyAutomator, windowController,
            getState = { latestState },
            playstyleInfo = playstyleInfo,
        )

        startTimeMs = System.currentTimeMillis()
        saveBrawlerData(queueData)

        stateThread = HandlerThread("pyla-state-checker").apply { start() }
        Handler(stateThread.looper).post {
            try {
                stateCheckerLoop()
            } catch (t: Throwable) {
                PylaLog.e(TAG, "state checker died", t)
                BotStatus.error("State checker failed: ${t.message}")
            }
        }

        PylaLog.p(TAG, "Initialization complete, starting main loop. queue=${queueData.size} brawlers")
        queueData.forEachIndexed { i, m -> PylaLog.p(TAG, "queue[$i]: brawler=${m["brawler"]} type=${m["type"]} trophies=${m["trophies"]} push_until=${m["push_until"]} auto=${m["automatically_pick"]}") }
        mainLoop()
    }

    fun stop() {
        stopRequested.set(true)
        try { if (::windowController.isInitialized) windowController.releaseMovement() } catch (_: Throwable) {}
        if (::loopThread.isInitialized) loopThread.quitSafely()
        if (::stateThread.isInitialized) stateThread.quitSafely()
        instance = null
        BotStatus.engineRunning = false
        BotStatus.currentState = ""
        Log.i(TAG, "Bot engine stopped")
    }

    fun setPaused(p: Boolean) { paused.set(p) }

    private fun stateCheckerLoop() {
        var loggedNoCapture = false
        var loggedFirstFrame = false
        var lastLoggedState: String? = null
        val timeConfig = PylaConfig.load("cfg/time_tresholds.toml")
        val stateCheckIntervalMs =
            (timeConfig.getDouble("state_check", 1.0) * 1000).toLong().coerceAtLeast(250L)
        val idleIntervalMs =
            (timeConfig.getDouble("idle", 3.0) * 1000).toLong().coerceAtLeast(1000L)
        val noDetectionsSec = timeConfig.getDouble("no_detections", 30.0).coerceAtLeast(10.0)
        var lastStateCheckMs = 0L
        var lastIdleCheckMs = 0L
        var lastNoDetectionsCheckMs = 0L
        while (!stopRequested.get()) {
            try {
                gameWatchdog()
                val fg = com.pyla.ai.input.InputService.foregroundPackage()
                val gameInFront = fg == null || fg == gamePackage || fg == appContext.packageName
                val now = System.currentTimeMillis()
                val capture = CaptureService.instance
                if (capture == null) {
                    if (!loggedNoCapture) { PylaLog.w(TAG, "state checker: CaptureService.instance is null"); loggedNoCapture = true }
                } else if (gameInFront && now - lastStateCheckMs >= stateCheckIntervalMs) {
                    loggedNoCapture = false
                    val frame = capture.latestFrame()
                    val buf = frame?.rgbBuffer
                    if (frame != null && buf != null) {
                        lastStateCheckMs = now
                        if (!loggedFirstFrame) { PylaLog.p(TAG, "state checker received first frame ${frame.width}x${frame.height}"); loggedFirstFrame = true }
                        val mat = PylaUtils.argbToRgbMat(buf, frame.width, frame.height)
                        val state = StateFinder.getState(mat)
                        synchronized(stateLock) { latestState = state }
                        BotStatus.currentState = state
                        BotStatus.frameCount++
                        if (state != lastLoggedState) {
                            PylaLog.p(TAG, "state transition: ${lastLoggedState ?: "<none>"} -> $state")
                            lastLoggedState = state
                        }
                        if (now - lastIdleCheckMs >= idleIntervalMs) {
                            lastIdleCheckMs = now
                            try { lobbyAutomator.checkForIdle(mat) } catch (t: Throwable) { PylaLog.w(TAG, "idle check: ${t.message}") }
                        }
                        mat.release()
                        if (now - lastNoDetectionsCheckMs >= noDetectionsSec * 1000) {
                            lastNoDetectionsCheckMs = now
                            if (state == "match" && ::play.isInitialized) {
                                val t = System.currentTimeMillis() / 1000.0
                                val stale = play.timeSinceDetections.values.any { t - it > noDetectionsSec }
                                if (stale && now - lastGameLaunchMs >= GAME_RELAUNCH_COOLDOWN_MS) {
                                    PylaLog.w(TAG, "No detections for ${noDetectionsSec.toInt()}s in match, reopening Brawl Stars")
                                    BotStatus.action("No detections for ${noDetectionsSec.toInt()}s, reopening Brawl Stars")
                                    if (GameLauncher.launch(appContext, gamePackage)) lastGameLaunchMs = now
                                    for (k in play.timeSinceDetections.keys) play.timeSinceDetections[k] = t
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                PylaLog.e(TAG, "state checker exception", t)
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
    }


    private fun gameWatchdog() {
        val fg = com.pyla.ai.input.InputService.foregroundPackage() ?: return
        val now = System.currentTimeMillis()
        if (fg == gamePackage || fg == appContext.packageName) {
            gameLostFocusSinceMs = 0L
            return
        }
        if (gameLostFocusSinceMs == 0L) {
            gameLostFocusSinceMs = now
            return
        }
        if (now - gameLostFocusSinceMs >= GAME_REFOCUS_DELAY_MS && now - lastGameLaunchMs >= GAME_RELAUNCH_COOLDOWN_MS) {
            PylaLog.w(TAG, "Brawl Stars not in foreground (current=$fg), relaunching")
            BotStatus.action("Relaunching Brawl Stars (foreground was $fg)")
            if (GameLauncher.launch(appContext, gamePackage)) {
                lastGameLaunchMs = now
            } else {
                lastGameLaunchMs = now + 60_000L
            }
            gameLostFocusSinceMs = 0L
        }
    }

    private fun mainLoop() {
        var pickedFirstBrawler = false
        var firstFrame = true
        var loggedWaitingForGame = false
        var seenRealState = false
        var loggedWaitingForLoad = false
        while (!stopRequested.get()) {
            if (paused.get()) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            val fg = com.pyla.ai.input.InputService.foregroundPackage()
            if (fg != null && fg != gamePackage && fg != appContext.packageName) {
                if (!loggedWaitingForGame) {
                    PylaLog.p(TAG, "main loop idle: waiting for $gamePackage in foreground (current=$fg)")
                    BotStatus.action("Waiting for Brawl Stars (foreground: $fg)")
                    loggedWaitingForGame = true
                }
                try { if (::windowController.isInitialized) windowController.releaseMovement() } catch (_: Throwable) {}
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                continue
            }
            loggedWaitingForGame = false
            try {
                val frame = windowController.screenshot()
                if (firstFrame) {
                    firstFrame = false; startTimeMs = System.currentTimeMillis()
                    BotStatus.captureSize = "${frame.width}x${frame.height}"
                    PylaLog.p(TAG, "main loop first screenshot: ${frame.width}x${frame.height} ratios=(${"%.2f".format(windowController.widthRatio)}, ${"%.2f".format(windowController.heightRatio)}) scale=${"%.2f".format(windowController.scaleFactor)}")
                }

                val state = latestState
                if (state == null) { Thread.sleep(50); continue }

                if (!seenRealState) {
                    if (state == "match") {
                        if (!loggedWaitingForLoad) {
                            PylaLog.p(TAG, "Startup: no known screen recognized yet, waiting for the game to load")
                            BotStatus.action("Waiting for the game to load...")
                            loggedWaitingForLoad = true
                        }
                        Thread.sleep(500)
                        continue
                    }
                    PylaLog.p(TAG, "Startup: first recognized screen is '$state'")
                    seenRealState = true
                }

                if (state == "lobby" && !pickedFirstBrawler) {
                    val first = stageManager.brawlersPickData.firstOrNull()
                    if (first == null) { PylaLog.w(TAG, "Empty queue -- stopping"); stop(); return }
                    pickedFirstBrawler = true
                    stageManager.updateTrophyObserver()
                    val autoPick = toBool(first["automatically_pick"], false)
                    PylaLog.p(TAG, "First lobby reached: brawler=${first["brawler"]} automatically_pick=$autoPick")
                    if (autoPick) {
                        val nextBrawler = first["brawler"].toString()
                        PylaLog.p(TAG, "Picking brawler automatically: $nextBrawler")
                        BotStatus.action("Picking brawler: $nextBrawler")
                        val result = lobbyAutomator.selectBrawler(nextBrawler)
                        PylaLog.p(TAG, "Brawler selection result: $result")
                        if (result != "success") {
                            PylaLog.w(TAG, "First selection did not complete ($result), continuing with the brawler selected in game")
                        }
                    } else {
                        PylaLog.p(TAG, "Auto-pick is off, using the brawler currently selected in game")
                    }
                }

                if (runForMinutes > 0 && !inCooldown) {
                    val elapsedMin = (System.currentTimeMillis() - startTimeMs) / 60_000.0
                    if (elapsedMin >= runForMinutes) {
                        Log.i(TAG, "run_for_minutes reached; entering 3-min cooldown")
                        inCooldown = true
                        cooldownStartMs = System.currentTimeMillis()
                    }
                }
                if (inCooldown && System.currentTimeMillis() - cooldownStartMs >= cooldownDurationMs) {
                    Log.i(TAG, "cooldown over, stopping bot")
                    stop(); return
                }

                if (state == "match") {
                    val brawler = stageManager.brawlersPickData.firstOrNull()?.get("brawler")?.toString()
                    if (brawler == null) { Thread.sleep(50); continue }
                    val mat = PylaUtils.frameToMat(frame)
                    val t0 = System.currentTimeMillis()
                    try {
                        play.main(mat, brawler, state) { newState ->
                            synchronized(stateLock) { latestState = newState }
                        }
                    } finally {
                        mat.release()
                    }
                    matchLoopMs += System.currentTimeMillis() - t0
                    if (++matchLoopSamples >= 50) {
                        val avg = matchLoopMs / matchLoopSamples
                        PylaLog.p(TAG, "match loop avg ${avg}ms (~${"%.1f".format(1000.0 / avg.coerceAtLeast(1))} fps)")
                        matchLoopMs = 0; matchLoopSamples = 0
                    }
                } else if (state != "match_making") {
                    PylaLog.p(TAG, "main loop: dispatching state=$state to StageManager")
                    stageManager.doState(state)
                }
            } catch (t: Throwable) {
                if (t is IllegalStateException && t.message?.contains("push targets reached") == true) {
                    PylaLog.p(TAG, "All brawler push targets reached, stopping the bot")
                    BotStatus.error("All brawler push targets reached, bot stopped")
                    stop()
                    return
                }
                PylaLog.e(TAG, "main loop error: ${t.message}", t)
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun toBool(x: Any?, default: Boolean = false): Boolean = when (x) {
        is Boolean -> x
        is String -> x.trim().lowercase() in setOf("1", "true", "yes", "on")
        is Number -> x.toInt() != 0
        null -> default
        else -> default
    }
}