package com.pyla.ai.engine

import com.pyla.ai.diagnostics.PipelineTimingTelemetry

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.pyla.ai.engine.PylaLog
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.PlaystyleSelection
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.pyla.PlaystyleRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BotEngine(
    val appContext: Context,
    var queueData: MutableList<MutableMap<String, Any>>,
) {
    companion object {
        private const val TAG = "PylaEngine"
        private const val CAPTURE_STARTUP_TIMEOUT_MS = 15_000L
        private const val NO_DETECTIONS_ACTION_THRESHOLD_SEC = 8 * 60.0
        private val sessionOwnershipLock = Any()
        @Volatile var instance: BotEngine? = null
            private set

        /**
         * Maximum age of the admitted-match evidence that may authorise a no-detections recovery.
         */
        const val STALE_RECOVERY_MAX_EVIDENCE_AGE_MS = 3_000L

        /**
         * Decides whether the no-detections recovery may fire, from evidence only.
         *
         * The recovery used to be gated on `state == "match"`, which the classifier can no longer
         * return, so it became dead code. Re-gating it on a fresh *admitted* match is sound only if
         * every input is evidence:
         *  * [priorAdmittedState] must be "match", and only an actual Play callback can write that -
         *    the classifier never produces it, and a cached or startup state is null/other,
         *  * [priorObservationAtMs] must be that same observation's capture timestamp: present, not
         *    in the future, and no older than [STALE_RECOVERY_MAX_EVIDENCE_AGE_MS] on the same clock,
         *  * [observedClassifier] must be "unknown" on the *current* frame. A known menu screen is
         *    positive evidence that gameplay is not running, so it can never authorise recovery,
         *  * the published status flag is deliberately not an input: it is a UI rendering of the
         *    decision, and using it would let an unverified claim authorise a relaunch.
         */
        internal fun staleRecoveryAuthorized(
            priorAdmittedState: String?,
            priorObservationAtMs: Long,
            observedClassifier: String,
            nowElapsedMs: Long,
        ): Boolean {
            if (priorAdmittedState != "match") return false
            if (priorObservationAtMs <= 0L) return false
            if (priorObservationAtMs > nowElapsedMs) return false
            if (nowElapsedMs - priorObservationAtMs > STALE_RECOVERY_MAX_EVIDENCE_AGE_MS) return false
            return observedClassifier == "unknown"
        }

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
                DiagnosticLog.failure("engine", "queue_load", "json_decode_threw", t)
                Log.w(TAG, "load queue: ${t.message}")
                mutableListOf()
            }
        }
    }

    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stateLock = Any()
    private val stateAuthority = GameStateAuthority()
    private var stateRevision = 0L // Protected by stateLock; binds observations across Pause/Resume.

    // Per-session lifecycle authority for engine-initiated side effects (crash/no-detection
    // relaunch and idle reconnect). It fails closed: an unconfigured or non-owning session denies.
    // Each side effect binds its own request via beginRequest() and re-evaluates it immediately
    // before acting and again at queued dispatch — a boolean sampled once before a loop cannot
    // revoke a retained request across an unsampled Pause->Resume.
    private val sessionAuthority = EngineSessionAuthority(
        sessionLive = { instance === this && !stopRequested.get() && !paused.get() },
    )

    // Settled-session ownership: the instance is retained until BOTH the main worker's resource
    // cleanup AND the state-checker worker have settled, then cleared iff it is still the owner.
    // A delayed cleanup from an old session therefore cannot clear a new owner, reset new status or
    // release newly-owned input.
    @Volatile private var started = false
    private val mainWorkerSettled = AtomicBoolean(false)
    private val stateCheckerSettled = AtomicBoolean(false)
    private val fullySettled = AtomicBoolean(false)
    @Volatile private var stateWorkerStarted = false

    /** True while this instance has been started and has not fully settled. */
    fun isActive(): Boolean = started && !fullySettled.get()

    private val latestObservationAtMs: Long
        get() = stateAuthority.snapshot(SystemClock.elapsedRealtime()).capturedAtMs
    val latestState: String?
        get() = stateAuthority.snapshot(SystemClock.elapsedRealtime()).state

    private fun gameplayInputAllowed(): Boolean = instance === this &&
        !stopRequested.get() && !paused.get() &&
        stateAuthority.snapshot(SystemClock.elapsedRealtime()).state == "match"

    private fun observeState(observed: String, capturedAtMs: Long, gameplay: Boolean = false,
        expectedRevision: Long? = null): String? {
        val snapshot = synchronized(stateLock) {
            if (instance !== this || stopRequested.get() || paused.get() ||
                (expectedRevision != null && expectedRevision != stateRevision)) return latestState
            val now = SystemClock.elapsedRealtime()
            val next = if (gameplay && (observed == "match" || observed == "unknown"))
                stateAuthority.gameplay(observed == "match", capturedAtMs, now)
            else stateAuthority.menu(observed, capturedAtMs, now)
            if (::stageManager.isInitialized) stageManager.navigationStateObserved(next.state)
            BotStatus.currentState = next.state ?: "unknown"
            if (next.state == "match") {
                if (BotStatus.verification.state != RuntimeState.MATCH || !BotStatus.verification.verified)
                    BotStatus.publish(RuntimeState.MATCH, VerificationPhase.VERIFIED, true)
            } else BotStatus.classifierObservation(next.state ?: "unknown", next.state != null && next.state != "unknown")
            next
        }
        if (observed != "unknown" && observed != "match" && snapshot.state != "match") {
            // Positive menu evidence revokes queued DOWNs even before its navigation pair completes.
            if (::play.isInitialized) play.resetCombat()
            if (::windowController.isInitialized) windowController.releaseMovement()
        }
        return snapshot.state
    }

    /**
     * Classify one frame and apply positive game/menu arbitration before the result is allowed to
     * touch authoritative state.
     *
     * The 3.0.4 Trio follower sequence was: admitted MATCH, a single heart-template
     * `brawler_selection` classification on an in-match frame, match authority revoked, and eleven
     * upper-left BACK taps. Play's admitted gameplay evidence is stronger than one ambiguous menu
     * template, so an UNCORROBORATED ambiguous-menu classification is reported as `unknown` while
     * that evidence is still fresh: by [GameStateAuthority]'s own contract `unknown` can neither
     * grant nor revoke gameplay, so the match survives and no menu navigation is dispatched.
     * Corroborated menu identity (two independent menu controls, no HUD control) is passed through
     * unchanged, so real lobby/shop/list transitions are not delayed.
     */
    private fun classifyAdmitted(mat: Mat, chaosGeneration: Long?, capturedAtMs: Long): String {
        val observation = StateFinder.getStateObservation(mat, chaosGeneration, capturedAtMs)
        val priorState = latestState
        val priorAt = latestObservationAtMs
        val admitted = MenuMatchArbitration.menuMayDisplaceMatch(
            priorState = priorState,
            priorEvidenceAtMs = priorAt,
            nowMs = SystemClock.elapsedRealtime(),
            observedState = observation.state,
            identity = observation.identity,
        )
        if (!admitted) {
            DiagnosticLog.sample(
                "state.menu_unconfirmed",
                "state=${observation.state} prior=$priorState level=${observation.identity.level}",
                5_000,
            )
            return "unknown"
        }
        return observation.state
    }

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
    private var effectiveMaxIps: Int = DeviceProfile.autoMaxIps
    private var thermalMaxIps: Int = effectiveMaxIps
    private var lastThermalCheckMs = 0L
    private var lastThermalLoggedLimit = -1
    private val powerManager: PowerManager? by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    private var crashCheckIntervalMs = 5_000L

    private val gamePackage: String get() = try { GameLauncher.gamePackage() } catch (t: Throwable) {
        // Read on the hot loop: rate-limited so a broken config cannot flood the bounded log.
        DiagnosticLog.sample("engine.setting_denied", "setting=game_package", 60_000)
        GameLauncher.DEFAULT_PACKAGE
    }
    private val runForMinutes: Int get() = try { PylaConfig.load("cfg/general_config.toml").getInt("run_for_minutes", 0) } catch (t: Throwable) {
        DiagnosticLog.sample("engine.setting_denied", "setting=run_for_minutes", 60_000)
        0
    }
    private var lastGameLaunchMs = 0L
    private var matchLoopMs = 0L
    private var matchLoopSamples = 0

    fun start() {
        // Claim only an empty slot, including while an old owner is tearing down. A closed
        // session is never restarted: its captured authorities are terminal by construction.
        val claimed = synchronized(sessionOwnershipLock) {
            if (instance != null || started || sessionAuthority.isClosed) false
            else {
                instance = this
                started = true
                true
            }
        }
        if (!claimed) {
            DiagnosticLog.failure("engine", "start", "ownership_unavailable")
            PylaLog.w(TAG, "start() ignored: engine ownership is unavailable")
            return
        }
        PipelineTimingTelemetry.reset()
        // Session replacement: a new run must not inherit the previous run's Chaosdrop evidence.
        synchronized(sessionOwnershipLock) {
            if (instance === this) PhoneVisualCues.revokeChaosContinuation()
        }
        fullySettled.set(false)
        stateWorkerStarted = false
        mainWorkerSettled.set(false)
        stateCheckerSettled.set(false)
        BotStatus.engineRunning = true
        BotStatus.enginePaused = false
        BotStatus.lastError = ""
        BotStatus.resetVerification()
        BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.LOADING, false)
        // New run: drop every previous run's HUD sample. Writers that started before this reset hold
        // the older generation and their samples are discarded on completion.
        resetHudTelemetry(announcePlaystyle = false)
        stateAuthority.reset()
        BotStatus.bindGameplayStatusGuard { gameplayInputAllowed() }
        BotStatus.inputConnected = com.pyla.ai.input.InputService.isConnected()
        BotStatus.queueSummary = queueData.joinToString(", ") { it["brawler"].toString() }

        DiagnosticLog.event("engine.start", "game=$gamePackage queue=${queueData.size} input=${BotStatus.inputConnected}")
        PylaLog.p(TAG, "Auto-opening Brawl Stars ($gamePackage)")
        val startup = sessionAuthority.beginRequest()
        if (startup != null && startup.allows()) {
            if (GameLauncher.launch(appContext, gamePackage)) lastGameLaunchMs = System.currentTimeMillis()
            else DiagnosticLog.failure("engine", "start", "game_launch_refused")
        } else {
            DiagnosticLog.failure("engine", "start", "authority_refused")
        }
        loopThread = HandlerThread("pyla-bot-loop").apply { start() }
        val posted = Handler(loopThread.looper).post {
            try {
                bootstrapAndRun()
            } catch (t: Throwable) {
                DiagnosticLog.failure("engine", "bootstrap", "unhandled", t)
                PylaLog.e(TAG, "engine bootstrap failed", t)
                if (instance === this) BotStatus.error("Engine start failed: ${t.message}")
                try { stop("bootstrap_failure") } catch (_: Throwable) {}
            } finally {
                cleanupResources()
                // If the state worker never started (early return, or its post was refused) it can
                // never settle itself; settle it here so ownership can complete.
                if (!stateWorkerStarted) markStateCheckerSettled()
                markMainWorkerSettled()
            }
        }
        if (!posted) {
            DiagnosticLog.failure("engine", "main_worker", "post_refused")
            // The loop thread was not running: the bootstrap can never execute. Settle now so a
            // following start() is not blocked by a phantom active session.
            if (!stateWorkerStarted) markStateCheckerSettled()
            markMainWorkerSettled()
        }
    }

    private fun bootstrapAndRun() {
        val deadlineMs = System.currentTimeMillis() + CAPTURE_STARTUP_TIMEOUT_MS
        var capture = CaptureService.instance
        while (capture == null && !stopRequested.get()) {
            if (System.currentTimeMillis() > deadlineMs) {
                DiagnosticLog.failure("engine", "capture_start", "timeout")
                Log.e(TAG, "CaptureService did not come up within ${CAPTURE_STARTUP_TIMEOUT_MS / 1000}s")
                BotStatus.error("CaptureService not running (screen capture permission not granted?)")
                stop("capture_unavailable")
                return
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
            capture = CaptureService.instance
        }
        if (capture == null) return
        PylaLog.p(TAG, "CaptureService is up, initializing engine")

        windowController = WindowController(capture)
        timeManagement = TimeManagement()
        lobbyAutomator = LobbyAutomation(windowController, selectionAllowed = { instance === this && !stopRequested.get() && !paused.get() })

        val generalConfig = PylaConfig.load("cfg/general_config.toml")
        effectiveMaxIps = generalConfig.getString("max_ips", "auto").trim().toIntOrNull()
            ?.takeIf { it > 0 }
            ?.coerceAtMost(60)
            ?: DeviceProfile.autoMaxIps
        thermalMaxIps = effectiveMaxIps
        PylaLog.p(TAG, "Runtime profile: ${DeviceProfile.summary()} effectiveMaxIps=$effectiveMaxIps")
        crashCheckIntervalMs = (PylaConfig.load("cfg/time_tresholds.toml")
            .getDouble("check_if_brawl_stars_crashed", 5.0) * 1000.0)
            .toLong().coerceAtLeast(10L)

        val selectedPlaystyle = PlaystyleSelection.effectivePlaystyle()
        val pylaScript = PlaystyleRegistry.load(selectedPlaystyle)
        if (pylaScript == null) {
            DiagnosticLog.failure("engine", "playstyle", "requested_unavailable")
            BotStatus.error("Selected playstyle could not be loaded. Import a compatible .pyla file.")
            PylaLog.e(TAG, "Selected playstyle failed to compile or could not be read; aborting start")
            stop("selected_playstyle_unavailable")
            return
        }
        DiagnosticLog.event(
            "playstyle.selected",
            "requested=$selectedPlaystyle loaded=${pylaScript.meta.filename} name=${pylaScript.meta.displayName} " +
                "gamemodes=${pylaScript.meta.gamemodes} fallback=${selectedPlaystyle != pylaScript.meta.filename}",
        )
        PylaLog.p(TAG, "Selected playstyle: ${pylaScript.meta.displayName} (gamemodes=${pylaScript.meta.gamemodes})")
        // Display exactly the script admitted above; an invalid selection aborts startup.
        runCatching { HudTelemetry.recordPlaystyle(HudTelemetry.generation(), pylaScript.meta.displayName) }

        applyConfiguredPlayOrder()
        val playstyleInfo = HashMap<String, Any>()
        playstyleInfo["name"] = "${pylaScript.meta.displayName} (Android)"
        playstyleInfo["gamemodes"] = pylaScript.meta.gamemodes
        playstyleInfo["brawlers"] = pylaScript.meta.brawlers.ifEmpty { listOf("all") }

        play = Play(
            windowController,
            mainInfoModelPath = "models/mainInGameModel.onnx",
            tileDetectorModelPath = "models/tileDetector.onnx",
            closeTileDetectorModelPath = "models/closeTileDetector.onnx",
            pylaScript = pylaScript,
            inputAllowed = { gameplayInputAllowed() },
        )
        stageManager = StageManager(
            queueData, lobbyAutomator, windowController,
            getState = { latestState },
            playstyleInfo = playstyleInfo,
            navigationAllowed = {
                instance === this && !stopRequested.get() && !paused.get() &&
                    com.pyla.ai.input.InputService.foregroundPackage() == gamePackage
            },
        )

        startTimeMs = System.currentTimeMillis()
        saveBrawlerData(queueData)

        stateThread = HandlerThread("pyla-state-checker", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val statePosted = Handler(stateThread.looper).post {
            try {
                stateCheckerLoop()
            } catch (t: Throwable) {
                PylaLog.e(TAG, "state checker died", t)
                if (instance === this) BotStatus.error("State checker failed: ${t.message}")
            } finally {
                markStateCheckerSettled()
            }
        }
        stateWorkerStarted = statePosted
        if (!statePosted) {
            DiagnosticLog.failure("engine", "state_worker", "post_refused")
            PylaLog.w(TAG, "state checker post refused; it will be settled by bootstrap")
        }

        PylaLog.p(TAG, "Initialization complete, starting main loop. queue=${queueData.size} brawlers")
        queueData.forEachIndexed { i, m -> PylaLog.p(TAG, "queue[$i]: brawler=${m["brawler"]} type=${m["type"]} trophies=${m["trophies"]} push_until=${m["push_until"]} auto=${m["automatically_pick"]}") }
        mainLoop()
    }

    /** Caller-attributed stop: one fixed, never-folded reason code, then the identical stop path. */
    fun stop(cause: String) {
        // Revoke/release first. Text observation must not precede the existing STOP boundary.
        try { stop() } finally {
            DiagnosticLog.terminal("engine", "stop", "requested", cause)
        }
    }

    fun stop() {
        val firstStop = !stopRequested.getAndSet(true)
        // Terminal lifecycle revocation: every retained/in-flight request is denied forever.
        sessionAuthority.close()
        if (::stageManager.isInitialized) stageManager.invalidateNavigation()
        stateAuthority.invalidate(SystemClock.elapsedRealtime())
        // Release owned input exactly once, while this instance is still the owner. Cleanup of an
        // old session must never release input newly owned by a later session; retaining ownership
        // until full settlement guarantees that here.
        val owner = instance === this
        if (owner && firstStop) {
            // Stop invalidates the Chaosdrop continuation evidence. Owner-gated: a stale stop must
            // not poison a newer session's own evidence.
            synchronized(sessionOwnershipLock) {
                if (instance === this) PhoneVisualCues.revokeChaosContinuation()
            }
            try { if (::play.isInitialized) play.revokeCombat() } catch (_: Throwable) {}
            try { if (::windowController.isInitialized) windowController.releaseMovement() } catch (_: Throwable) {}
        }
        // quitSafely() drains pending messages but does NOT wake a loop blocked in Thread.sleep;
        // interrupt so both workers actually observe stopRequested and settle.
        if (::loopThread.isInitialized) { loopThread.quitSafely(); loopThread.interrupt() }
        if (::stateThread.isInitialized) { stateThread.quitSafely(); stateThread.interrupt() }
        // Publish stop status only as the owner, so an obsolete stop cannot reset a new session.
        if (owner) {
            BotStatus.engineRunning = false
            BotStatus.enginePaused = false
            BotStatus.currentState = ""
            BotStatus.resetVerification()
            resetHudTelemetry(announcePlaystyle = false)
        }
        if (owner && firstStop) {
            PipelineTimingTelemetry.interruption("STOP")
            PipelineTimingTelemetry.flush()
        }
        DiagnosticLog.event("engine.stop", "stopRequested=${stopRequested.get()}")
        Log.i(TAG, "Bot engine stopped")
    }

    /**
     * Settle the main worker (resource cleanup finished) and, once BOTH workers have settled, clear
     * ownership iff this instance is still the owner. Obsolete completion therefore cannot clear a
     * new owner, reset new status or release newly-owned input.
     */
    private fun markMainWorkerSettled() {
        mainWorkerSettled.set(true)
        maybeCompleteSettlement()
    }

    private fun markStateCheckerSettled() {
        stateCheckerSettled.set(true)
        maybeCompleteSettlement()
    }

    private fun maybeCompleteSettlement() {
        if (!mainWorkerSettled.get() || !stateCheckerSettled.get()) return
        if (!fullySettled.compareAndSet(false, true)) return
        // Reset status BEFORE releasing ownership. Otherwise a new owner can publish its running
        // status between instance=null and these writes, only to have it erased by this worker.
        if (instance === this) {
            BotStatus.engineRunning = false
            BotStatus.enginePaused = false
            BotStatus.currentState = ""
            BotStatus.resetVerification()
        }
        synchronized(sessionOwnershipLock) {
            if (instance === this) instance = null
        }
    }

    private fun cleanupResources() {
        try {
            if (::play.isInitialized) play.close()
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "cleanup", "play_close_threw", t)
            Log.w(TAG, "play cleanup: ${t.message}")
        }
        try {
            if (::lobbyAutomator.isInitialized) lobbyAutomator.close()
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "cleanup", "lobby_close_threw", t)
            Log.w(TAG, "OCR cleanup: ${t.message}")
        }
        try {
            if (::stageManager.isInitialized) stageManager.close()
        } catch (t: Throwable) {
            DiagnosticLog.failure("engine", "cleanup", "stage_close_threw", t)
            Log.w(TAG, "stage cleanup: ${t.message}")
        }
    }

    fun setPaused(p: Boolean) {
        // Invalidate on BOTH Pause and Resume: a request admitted before an unsampled
        // Pause->Resume pair is bound to a superseded epoch and can never be revived.
        sessionAuthority.invalidate()
        synchronized(stateLock) {
            paused.set(p)
            stateRevision++
            stateAuthority.invalidate(SystemClock.elapsedRealtime())
        }
        if (::stageManager.isInitialized) stageManager.invalidateNavigation()
        if (instance === this) {
            // Pause AND Resume invalidate the Chaosdrop continuation: evidence observed before an
            // unsampled Pause->Resume pair must not authorise a coins dismissal afterwards.
            // Owner-gated so a stale instance cannot poison a newer session's own evidence.
            synchronized(sessionOwnershipLock) {
                if (instance === this) PhoneVisualCues.revokeChaosContinuation()
            }
            if (p && ::play.isInitialized) play.resetCombat()
            if (p && ::windowController.isInitialized) windowController.releaseMovement()
            BotStatus.enginePaused = p && BotStatus.engineRunning
            // Pause and resume are both lifecycle boundaries: nothing measured before either may be
            // presented afterwards. Resume re-announces the already loaded playstyle metadata.
            resetHudTelemetry(announcePlaystyle = !p)
            BotStatus.action(if (p) "Bot paused" else "Bot resumed")
            if (p) {
                PipelineTimingTelemetry.interruption("PAUSED")
                PipelineTimingTelemetry.flush()
            }
        }
    }

    fun isPaused(): Boolean = paused.get()

    /**
     * HUD telemetry lifecycle boundary. Resetting advances the telemetry generation, so a loop
     * iteration or gameplay frame that started before this point can no longer publish its sample.
     * [announcePlaystyle] re-publishes the already loaded script metadata on resume; without it the
     * detail row would stay empty until the next playstyle change.
     */
    private fun resetHudTelemetry(announcePlaystyle: Boolean) {
        HudTelemetry.reset()
        if (!announcePlaystyle) return
        val script = try { if (::play.isInitialized) play.pylaScript else null } catch (_: Throwable) { null }
        if (script != null) HudTelemetry.recordPlaystyle(HudTelemetry.generation(), script.meta.displayName)
    }

    private fun stateCheckerLoop() {
        var loggedNoCapture = false
        var loggedFirstFrame = false
        var lastLoggedState: String? = null
        val timeConfig = PylaConfig.load("cfg/time_tresholds.toml")
        val stateCheckIntervalMs =
            (timeConfig.getDouble("state_check", 3.0) * 1000).toLong().coerceAtLeast(10L)
        val idleIntervalMs =
            (timeConfig.getDouble("idle", 3.0) * 1000).toLong().coerceAtLeast(10L)
        // As on PC, no_detections controls how often stale detections are checked. The actual
        // recovery threshold is eight minutes, not the check interval itself.
        val noDetectionsCheckIntervalMs =
            (timeConfig.getDouble("no_detections", 30.0) * 1000).toLong().coerceAtLeast(10L)
        var lastStateCheckMs = 0L
        var lastIdleCheckMs = 0L
        var lastNoDetectionsCheckMs = 0L
        var lastCrashCheckMs = 0L
        var stateFrameBuffer: IntArray? = null
        while (!stopRequested.get()) {
            if (instance !== this) return
            val iteration = sessionAuthority.beginRequest() ?: return
            if (!iteration.allows()) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            val observationRevision = synchronized(stateLock) { stateRevision }
            try {
                val now = System.currentTimeMillis()
                if (now - lastCrashCheckMs >= crashCheckIntervalMs) {
                    lastCrashCheckMs = now
                    gameWatchdog(now)
                }
                val fg = com.pyla.ai.input.InputService.foregroundPackage()
                val gameInFront = fg == null || fg == gamePackage || fg == appContext.packageName
                val capture = CaptureService.instance
                if (capture == null) {
                    if (!loggedNoCapture) {
                        DiagnosticLog.sample("engine.capture_unavailable", "phase=state_checker reason=service_absent", 30_000)
                        PylaLog.w(TAG, "state checker: CaptureService.instance is null"); loggedNoCapture = true
                    }
                } else if (gameInFront && now - lastStateCheckMs >=
                    if (latestState == null || latestState == "unknown") minOf(stateCheckIntervalMs, 500L) else stateCheckIntervalMs) {
                    loggedNoCapture = false
                    // Bind this observation to the lifecycle generation captured BEFORE the frame:
                    // an in-flight classification that straddled a Pause/Stop/session replacement
                    // can neither arm nor continue the Chaosdrop coins dismissal.
                    val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()
                    val copied = capture.copyLatestFrame(stateFrameBuffer)
                    if (copied != null &&
                        SystemClock.elapsedRealtime() - copied.capturedAtMs <= WindowController.FRAME_STALE_TIMEOUT_MS
                    ) {
                        stateFrameBuffer = copied.rgbBuffer
                        lastStateCheckMs = now
                        if (!loggedFirstFrame) { PylaLog.p(TAG, "state checker received first frame ${copied.width}x${copied.height}"); loggedFirstFrame = true }
                        val mat = PylaUtils.argbToRgbMat(copied.rgbBuffer, copied.width, copied.height)
                        val observed = classifyAdmitted(mat, chaosGeneration, copied.capturedAtMs)
                        // Snapshot the *previous* admitted evidence before this frame can overwrite
                        // it. The recovery below is about a match that was positively admitted and
                        // has since stopped producing detections, so it must be judged on what was
                        // known before this (now unknown) classifier result was written.
                        var priorAdmittedState: String? = null
                        var priorObservationAtMs = 0L
                        synchronized(stateLock) {
                            priorAdmittedState = latestState
                            priorObservationAtMs = latestObservationAtMs
                        }
                        if (!iteration.allows()) { mat.release(); continue }
                        val state = observeState(observed, copied.capturedAtMs, expectedRevision = observationRevision) ?: "unknown"
                        BotStatus.frameCount++
                        if (state != lastLoggedState) {
                            DiagnosticLog.event("state.transition", "${lastLoggedState ?: "<none>"} -> $state")
                            PylaLog.p(TAG, "state transition: ${lastLoggedState ?: "<none>"} -> $state")
                            lastLoggedState = state
                        }
                        if (now - lastIdleCheckMs >= idleIntervalMs) {
                            lastIdleCheckMs = now
                            // Bind this single reconnect to the current lifecycle epoch and carry the
                            // guard, with the actual foreground + the original capture timestamp +
                            // freshness, all the way to the non-replayable navigation queue. An
                            // unsampled Pause->Resume, a stop, or a foreground loss denies it.
                            val bound = iteration
                            val idleGuard: () -> Boolean = run {
                                val capturedAtMs = copied.capturedAtMs
                                {
                                    bound.allows() &&
                                        com.pyla.ai.input.InputService.foregroundPackage() == gamePackage &&
                                        capturedAtMs > 0L &&
                                        SystemClock.elapsedRealtime() - capturedAtMs in 0L..WindowController.FRAME_STALE_TIMEOUT_MS
                                }
                            }
                            try {
                                lobbyAutomator.checkForIdle(mat, idleGuard)
                            } catch (t: Throwable) {
                                DiagnosticLog.failure("engine", "idle_check", "threw", t)
                                PylaLog.w(TAG, "idle check: ${t.message}")
                            }
                        }
                        mat.release()
                        if (now - lastNoDetectionsCheckMs >= noDetectionsCheckIntervalMs) {
                            lastNoDetectionsCheckMs = now
                            // Fresh positively admitted Play evidence only. A known menu screen,
                            // a stale observation or a startup/cached state cannot authorise this.
                            val recoveryAllowed = staleRecoveryAuthorized(
                                priorAdmittedState, priorObservationAtMs, observed,
                                SystemClock.elapsedRealtime(),
                            )
                            // Bind a per-request lifecycle guard; a Pause/Stop before the effect
                            // revokes it, and it is re-evaluated immediately before the relaunch.
                            val recoveryGuard: () -> Boolean = { iteration.allows() }
                            if (recoveryAllowed && ::play.isInitialized) {
                                val t = System.currentTimeMillis() / 1000.0
                                val stale = play.timeSinceDetections.values.any {
                                    t - it > NO_DETECTIONS_ACTION_THRESHOLD_SEC
                                }
                                if (stale && recoveryGuard()) {
                                    val timeout = NO_DETECTIONS_ACTION_THRESHOLD_SEC.toInt()
                                    PylaLog.w(TAG, "No detections for ${timeout}s in match, reopening Brawl Stars")
                                    BotStatus.action("No detections for ${timeout}s, reopening Brawl Stars")
                                    if (recoveryGuard()) {
                                        if (GameLauncher.launch(appContext, gamePackage)) lastGameLaunchMs = now
                                        else DiagnosticLog.failure("engine", "no_detections", "game_launch_refused")
                                    }
                                    for (k in play.timeSinceDetections.keys) play.timeSinceDetections[k] = t
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                DiagnosticLog.failure("engine", "state_checker", "unhandled", t)
                PylaLog.e(TAG, "state checker exception", t)
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
    }


    internal fun gameWatchdog(now: Long) {
        // Bind a per-iteration lifecycle request; Pause/Stop (and non-ownership) deny it. A boolean
        // sampled once before the loop cannot revoke a retained request across a later Pause.
        val bound = sessionAuthority.beginRequest() ?: return
        if (!bound.allows()) return
        val fg = com.pyla.ai.input.InputService.foregroundPackage() ?: return
        if (fg == gamePackage || fg == appContext.packageName) return

        // The PC setting check_if_brawl_stars_crashed is the cadence for this check. If the game is
        // not foreground at that check, relaunch it immediately instead of applying a second hidden
        // Android-only delay.
        //
        // Recovery relaunch is legitimately authorized while a FOREIGN app is foreground, so game
        // foreground is deliberately NOT required here; only live session/ownership authorizes it.
        PylaLog.w(TAG, "Brawl Stars not in foreground (current=$fg), relaunching")
        // Enforce the guard once more immediately before the side effect (a Pause between binding
        // and here must deny the launch).
        if (!bound.allows()) return
        BotStatus.action("Relaunching Brawl Stars (foreground was $fg)")
        if (bound.allows()) {
            if (GameLauncher.launch(appContext, gamePackage)) lastGameLaunchMs = now
            else DiagnosticLog.failure("engine", "watchdog", "game_launch_refused")
        }
    }

    private fun mainLoop() {
        var pickedFirstBrawler = false
        var firstFrame = true
        var loggedWaitingForGame = false
        var seenRealState = false
        var loggedWaitingForLoad = false
        while (!stopRequested.get()) {
            if (instance !== this) return
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
            val iteration = sessionAuthority.beginRequest() ?: return
            val observationRevision = synchronized(stateLock) { stateRevision }
            val iterationStartNs = System.nanoTime()
            // HUD telemetry generation bound to this iteration: a Pause/Stop/start that resets the
            // recorder supersedes this value, so a late sample can never publish into a newer run.
            val telemetryCycle = HudTelemetry.generation()
            try {
                // Captured BEFORE the screenshot so a classification that straddled a Pause/Stop is
                // bound to (and rejected by) the superseded Chaosdrop lifecycle generation.
                val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()
                val frame = windowController.screenshot()
                if (!iteration.allows()) continue
                DiagnosticLog.sample("capture.frame", "age_ms=${frame.ageMs()} size=${frame.width}x${frame.height}")
                if (firstFrame) {
                    firstFrame = false; startTimeMs = System.currentTimeMillis()
                    if (instance === this) BotStatus.captureSize = "${frame.width}x${frame.height}"
                    PylaLog.p(TAG, "main loop first screenshot: ${frame.width}x${frame.height} ratios=(${"%.2f".format(windowController.widthRatio)}, ${"%.2f".format(windowController.heightRatio)}) scale=${"%.2f".format(windowController.scaleFactor)}")
                }

                var state = latestState
                if (state == null) { Thread.sleep(50); continue }
                if (state != "match" && state != "unknown") {
                    val currentMat = PylaUtils.frameToMat(frame)
                    val observedNow = try { classifyAdmitted(currentMat, chaosGeneration, frame.capturedAtMs) } finally { currentMat.release() }
                    state = observeState(observedNow, frame.capturedAtMs, expectedRevision = observationRevision) ?: "unknown"
                }
                if (state != "match" && state != "unknown" &&
                    SystemClock.elapsedRealtime() - latestObservationAtMs > 3_000L) {
                    windowController.releaseMovement()
                    if (instance === this) BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_STATE, false)
                    Thread.sleep(100)
                    continue
                }

                if (!seenRealState) {
                    if (state == "unknown") {
                        if (!loggedWaitingForLoad) {
                            PylaLog.p(TAG, "Startup: verifying the current game screen")
                            BotStatus.action("Verifying current game screen")
                            loggedWaitingForLoad = true
                        }
                        // Observe gameplay too: no menu template match is not proof of a match.
                        // Play admits it only after distinct fresh positive player observations.
                    } else {
                        PylaLog.p(TAG, "Startup: first verified screen is '$state'")
                        seenRealState = true
                    }
                }

                if (state == "lobby" && !pickedFirstBrawler) {
                    val first = stageManager.brawlersPickData.firstOrNull()
                    if (first == null) {
                        PylaLog.w(TAG, "Empty queue -- stopping")
                        stop("empty_queue")
                        return
                    }
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
                        if (result != "success") throw BrawlerSelectionFailedException(result)
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
                    stop("run_for_minutes"); return
                }

                // Re-check ownership/flags AFTER capture and long processing and BEFORE entering the
                // stage/play authority: a stale iteration captured before a Pause must not issue
                // fresh navigation or combat authority now.
                if (!iteration.allows()) continue

                if (state == "match" || state == "unknown") {
                    val brawler = stageManager.brawlersPickData.firstOrNull()?.get("brawler")?.toString()
                    if (brawler == null) { Thread.sleep(50); continue }
                    val mat = PylaUtils.frameToMat(frame)
                    val t0 = System.currentTimeMillis()
                    try {
                        play.main(mat, brawler, state, frame.capturedAtMs) { newState ->
                            observeState(newState, frame.capturedAtMs, gameplay = true)
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
                    // One completed gameplay-processing call: counts towards IPS and refreshes the
                    // capture-age sample. Stages this engine cannot measure stay unavailable (null)
                    // rather than inheriting another frame's cost.
                    runCatching {
                        val completedAtMs = SystemClock.elapsedRealtime()
                        HudTelemetry.recordGameplayFrame(
                            telemetryCycle, completedAtMs,
                            frameAgeMs = (completedAtMs - frame.capturedAtMs).takeIf { it >= 0L },
                        )
                    }
                } else if (state != "match_making") {
                    play.resetCombat()
                    windowController.releaseMovement()
                    BotStatus.publish(RuntimeState.fromClassifier(state), VerificationPhase.VERIFIED, true)
                    PylaLog.p(TAG, "main loop: dispatching state=$state to StageManager")
                    try {
                        stageManager.doState(state)
                    } catch (t: Throwable) {
                        // Caller-specific attribution for a Stage failure; the throw itself is unchanged.
                        DiagnosticLog.failure("engine", "stage_dispatch", "threw", t)
                        throw t
                    }
                }

                // LOOP processing time: the iteration body above, excluding the throttle sleep that
                // follows. Observation only; it never influences the throttle target itself.
                runCatching {
                    val elapsedNs = System.nanoTime() - iterationStartNs
                    HudTelemetry.recordLoop(telemetryCycle, SystemClock.elapsedRealtime(), elapsedNs / 1_000_000.0)
                }
            } catch (t: Throwable) {
                if (t is BrawlerSelectionFailedException) {
                    DiagnosticLog.failure("engine", "main_loop", "brawler_selection_unverified", t)
                    if (instance === this) BotStatus.error("Brawler selection could not be verified. Bot stopped; check the selection and press START again.")
                    PylaLog.w(TAG, "Unverified brawler selection (${t.reason}); refusing to start a match")
                    stop("brawler_selection_unverified")
                    return
                }
                if (t is IllegalStateException && t.message?.contains("push targets reached") == true) {
                    DiagnosticLog.failure("engine", "main_loop", "push_targets_reached", t)
                    PylaLog.p(TAG, "All brawler push targets reached, stopping the bot")
                    if (instance === this) BotStatus.error("All brawler push targets reached, bot stopped")
                    stop("push_targets_reached")
                    return
                }
                // Observation failure revokes pending combat as well as movement.
                try { play.resetCombat() } catch (_: Throwable) {}
                try { windowController.releaseMovement() } catch (_: Throwable) {}
                play.gameplayEvidence.reset()
                stateAuthority.invalidate(SystemClock.elapsedRealtime())
                DiagnosticLog.failure("engine", "main_loop", "unhandled", t)
                if (instance === this) BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_STATE, false)
                PylaLog.e(TAG, "main loop error: ${t.message}", t)
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
            throttleMainLoop(iterationStartNs)
        }
    }

    private fun throttleMainLoop(iterationStartNs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastThermalCheckMs >= 2_000L) {
            lastThermalCheckMs = nowMs
            thermalMaxIps = DeviceProfile.thermallySafeIps(effectiveMaxIps, powerManager)
            if (thermalMaxIps != lastThermalLoggedLimit) {
                if (lastThermalLoggedLimit >= 0 && thermalMaxIps < effectiveMaxIps) {
                    PylaLog.w(TAG, "Performance limited to $thermalMaxIps IPS due to heat or power saver")
                }
                lastThermalLoggedLimit = thermalMaxIps
            }
        }
        val targetNs = 1_000_000_000L / thermalMaxIps.coerceAtLeast(1)
        val remainingNs = targetNs - (System.nanoTime() - iterationStartNs)
        if (remainingNs <= 0L) return
        try {
            val millis = remainingNs / 1_000_000L
            val nanos = (remainingNs % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        } catch (_: InterruptedException) {}
    }

    /** Applies general_config.play_order exactly like PC apply_play_order(). */
    private fun applyConfiguredPlayOrder() {
        val order = PylaConfig.load("cfg/general_config.toml")
            .getString("play_order", "in_order").trim().lowercase()
        when (order) {
            "lowest_to_highest" -> queueData.sortBy { toInt(it["trophies"], 0) }
            "highest_to_lowest" -> queueData.sortByDescending { toInt(it["trophies"], 0) }
            else -> return
        }
        queueData.forEach { it["automatically_pick"] = true }
        BotStatus.queueSummary = queueData.joinToString(", ") { it["brawler"].toString() }
    }

    private fun toInt(x: Any?, default: Int = 0): Int = when (x) {
        is Number -> x.toInt()
        is String -> x.toIntOrNull() ?: default
        else -> default
    }

    private fun toBool(x: Any?, default: Boolean = false): Boolean = when (x) {
        is Boolean -> x
        is String -> x.trim().lowercase() in setOf("1", "true", "yes", "on")
        is Number -> x.toInt() != 0
        null -> default
        else -> default
    }
}
