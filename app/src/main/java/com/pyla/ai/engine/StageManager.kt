package com.pyla.ai.engine

import com.pyla.ai.engine.PylaLog
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.diagnostics.DiagnosticLog
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs

class StageManager(
    var brawlersPickData: MutableList<MutableMap<String, Any>>,
    val lobbyAutomator: LobbyAutomation,
    val windowController: WindowController,
    val getState: () -> String?,
    val playstyleInfo: Map<String, Any>,
    private val navigationAllowed: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "PylaStage"

        /** Granularity of the cancellation-aware Stage waits (all Stage waits poll live authority). */
        private const val NAV_WAIT_STEP_MS = 100L

        /** PC parity: the end screen must clear within this bounded, monotonic window. */
        private const val END_SCREEN_TIMEOUT_MS = 35_000L

        /** Distinct fresh captures required before the upper-left menu BACK may be dispatched. */
        private const val MENU_BACK_RECHECK_FRAMES = 2

        /** Spacing between those captures; keeps the pair inside the BACK admission age window. */
        private const val MENU_BACK_RECHECK_STEP_MS = 250L

        /**
         * Screens Stage is expected to navigate. A positive member is required for every menu action,
         * so a combat, startup or unclassified screen never authorises a menu tap on its own.
         */
        private val MENU_SCREENS = setOf(
            "lobby", "shop", "brawler_selection", "popup", "defeated", "trophy_reward",
            "prestige_milestone", "nano_noodles",
            "star_drop_regular", "star_drop_angelic", "star_drop_demonic", "star_drop_starr_nova",
            "end_victory", "end_defeat", "end_draw", "end_ranked", "reward_continue", "reward_reveal",
            "end_trio_showdown_0", "end_trio_showdown_1", "end_trio_showdown_2", "end_trio_showdown_3",
        )
    }

    val trophyObserver = TrophyObserver()
    private val defeatedExitGate = DefeatedExitGate()
    private val resultExitGate = DefeatedExitGate(screen = "end_ranked")
    private val rewardContinueGate = DefeatedExitGate(screen = "reward_continue")
    /** Positive menu identity gate for the upper-left BACK (see [quitShop]). */
    private val menuBackGate = MenuBackAdmission()
    @Volatile private var closed = false

    /**
     * Stage-owned lifecycle/queue authority for menu navigation.
     *
     * Every predicate is wired explicitly; the reviewed [NavigationAuthority] fails closed when a
     * predicate is left unwired. [sessionLive] carries the engine's [navigationAllowed] (which already
     * requires the game to be foreground, running and not Pause/Stop) plus this Stage's own terminal
     * `closed` flag. [menuLive] requires a positive current menu/screen classification, so foreground
     * equality alone can never authorise navigation. [foregroundLive] is stated as `true` because the
     * foreground requirement is already enforced inside [sessionLive]. The geometry source stays at
     * the authority default ([com.pyla.ai.capture.InputCoordinates.geometryGeneration]), so a
     * rotation/capture-size change revokes an outstanding request.
     */
    private val navigationAuthority = NavigationAuthority(
        sessionLive = { !closed && navigationAllowed() },
        menuLive = { getState() in MENU_SCREENS },
        foregroundLive = { true },
    )

    /**
     * Advance the Stage lifecycle epoch (Pause/Stop and other revocations) and the defeated-EXIT
     * evidence gate. Every navigation request issued before this call is denied forever, even if the
     * live predicates become true again, so an unsampled queued entry can never be renewed by Resume.
     */
    fun invalidateNavigation() {
        navigationAuthority.invalidate()
        defeatedExitGate.invalidate(android.os.SystemClock.elapsedRealtime())
        resultExitGate.invalidate(android.os.SystemClock.elapsedRealtime())
        rewardContinueGate.invalidate(android.os.SystemClock.elapsedRealtime())
        menuBackGate.invalidate()
    }

    fun navigationStateObserved(state: String?) {
        defeatedExitGate.screenChanged(state)
        resultExitGate.screenChanged(state)
        rewardContinueGate.screenChanged(state)
    }

    /**
     * One Stage navigation invocation, bound at its start to the lifecycle epoch current at that
     * moment.
     *
     * Every action inside the invocation reuses the SAME [NavigationAuthority.BoundRequest]. A
     * Pause/Stop that advances the epoch while the invocation is between actions — including during a
     * bounded wait — denies the whole invocation permanently, so a stale invocation can never renew
     * itself by issuing a fresh epoch request after a sleep. Each action also re-checks the current
     * screen and capture freshness at admission and dispatch, because the guard closure is handed to
     * the non-replayable navigation queue.
     */
    private inner class Invocation internal constructor(
        private val bound: NavigationAuthority.BoundRequest,
    ) {
        /** True while this invocation still owns the current epoch and every predicate holds. */
        val isLive: Boolean get() = bound.allows()

        /** Guard for one action that must still be a positive [expectedState] screen when used. */
        fun action(expectedState: String): () -> Boolean = {
            getState() == expectedState && bound.allows()
        }

        /**
         * Bounded, cancellation-aware wait. Returns `false` as soon as the invocation is no longer
         * live — Pause/Stop/close, a predicate false, a geometry change, an interrupt — and never
         * renews the invocation's authority. A monotonic deadline is not needed here because every
         * step re-checks liveness; the total is bounded by [ms].
         */
        fun await(ms: Long): Boolean {
            var remaining = ms
            while (remaining > 0) {
                if (!bound.allows()) return false
                val step = remaining.coerceAtMost(NAV_WAIT_STEP_MS)
                try {
                    Thread.sleep(step)
                } catch (t: InterruptedException) {
                    // The interrupt is the cancellation cause; the flag is restored exactly as before.
                    Thread.currentThread().interrupt()
                    DiagnosticLog.failure("stage", "await", "interrupted", t)
                    return false
                }
                remaining -= step
            }
            return bound.allows()
        }
    }

    /** Admit a navigation invocation bound to the epoch current right now, or fail closed. */
    private fun beginNavigation(): Invocation? {
        val bound = navigationAuthority.issue() ?: return null
        return Invocation(bound)
    }

    /** Visible enabled EXIT only, with two fresh captures and a queue-time revocation guard. */
    private fun exitDefeated(invocation: Invocation) =
        exitVisibleControl(invocation, "defeated", defeatedExitGate, "defeated_exit", StateFinder::findDefeatedExit)

    /** Ranked result screen (Rank / PLAY AGAIN / EXIT): leave through its visible EXIT. */
    private fun exitResult(invocation: Invocation) =
        exitVisibleControl(invocation, "end_ranked", resultExitGate, "result_exit", StateFinder::findResultExit)

    /** Reward presentation: leave through its visible CONTINUE, never through EQUIP/BUY. */
    private fun continueReward(invocation: Invocation) =
        exitVisibleControl(invocation, "reward_continue", rewardContinueGate, "reward_continue", StateFinder::findRewardContinue)

    private fun exitVisibleControl(
        invocation: Invocation, screen: String, gate: DefeatedExitGate, phase: String,
        locate: (Mat) -> org.opencv.core.Point?,
    ) {
        if (closed || !navigationAllowed() || getState() != screen) return
        val defeatedExitGate = gate
        val generation = defeatedExitGate.generation()
        val geometry = com.pyla.ai.capture.InputCoordinates.geometryGeneration
        val frame = windowController.screenshot()
        val mat = PylaUtils.frameToMat(frame)
        val target = try { locate(mat) } finally { mat.release() }
        val now = android.os.SystemClock.elapsedRealtime()
        val valid = target != null && !closed && navigationAllowed() && getState() == screen &&
            com.pyla.ai.capture.InputCoordinates.geometryGeneration == geometry
        val observation = DefeatedExitGate.Observation(
            frame.capturedAtMs, frame.width, frame.height,
            target?.x?.toFloat() ?: 0f, target?.y?.toFloat() ?: 0f, valid, geometry,
        )
        val ticket = defeatedExitGate.observe(observation, generation, now) ?: return
        val guard = {
            // New Stage lifecycle bound guard composed in: the original ticket/pixel/two-fresh-frame
            // evidence below is unchanged and still required.
            invocation.isLive &&
                !closed && navigationAllowed() && getState() == screen &&
                com.pyla.ai.capture.InputCoordinates.geometryGeneration == geometry &&
                com.pyla.ai.capture.InputCoordinates.captureWidth == frame.width &&
                com.pyla.ai.capture.InputCoordinates.captureHeight == frame.height &&
                defeatedExitGate.allows(ticket, android.os.SystemClock.elapsedRealtime())
        }
        if (!guard()) {
            DiagnosticLog.sample("navigation.denied", "phase=$phase reason=evidence_or_authority", 10_000)
            return
        }
        DiagnosticLog.event("navigation.$phase", "reason=visible_enabled_control attempt=${ticket.serial}")
        BotStatus.action(if (screen == "reward_continue") "Pressing visible CONTINUE" else "Pressing visible EXIT")
        windowController.guardedNavigationTap(observation.x, observation.y, guard)
    }

    private val playAgainOnWin: Boolean get() =
        PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("play_again_on_win"), false)

    private var closePopupIcon: Mat? = null
    private var timeSinceLastStatChange: Double = System.currentTimeMillis() / 1000.0

    private fun nowSec() = System.currentTimeMillis() / 1000.0

    fun updateTrophyObserver() {
        val data = brawlersPickData.firstOrNull() ?: return
        trophyObserver.winStreak = toInt(data["win_streak"], 0)
        trophyObserver.currentTrophies = toInt(data["trophies"], 0)
        trophyObserver.currentWins = toInt(data["wins"], 0)
    }

    fun doState(state: String) {
        defeatedExitGate.screenChanged(state)
        resultExitGate.screenChanged(state)
        rewardContinueGate.screenChanged(state)
        DiagnosticLog.event("stage.do_state", "state=$state queue=${brawlersPickData.size}")
        PylaLog.p(TAG, "doState: $state")
        BotStatus.action("doState: $state")
        // One invocation per dispatched state, bound to the lifecycle epoch current right now.
        val invocation = beginNavigation()
        if (invocation == null) {
            DiagnosticLog.failure("stage", "navigation", "authority_unavailable")
            return
        }
        when (state) {
            "shop", "brawler_selection" -> quitShop(invocation, state)
            "popup" -> closePopUp(invocation)
            "defeated" -> exitDefeated(invocation)
            "end_ranked" -> exitResult(invocation)
            "reward_continue" -> continueReward(invocation)
            "match", "match_making", "match_start", "result_preview", "transition" -> {}
            "lobby" -> startGame(invocation)
            "star_drop_regular", "star_drop_angelic", "star_drop_demonic", "star_drop_starr_nova" ->
                clickStarDrop(invocation, state)
            "trophy_reward", "reward_reveal" ->
                windowController.guardedNavigationPress("proceed", 20L, invocation.action(state))
            "prestige_milestone" -> handlePrestige(invocation)
            "nano_noodles" -> clickNanoNoodles(invocation, state)
            "end_victory", "end_defeat", "end_draw", "end_trio_showdown_0", "end_trio_showdown_1",
            "end_trio_showdown_2", "end_trio_showdown_3" -> endGame(invocation, state)
        }
    }

    private fun startGame(invocation: Invocation) {
        val currentBrawler = brawlersPickData[0]["brawler"].toString()
        val typeOfPush = brawlersPickData[0]["type"].toString()
        val currentValue = if (typeOfPush == "trophies") trophyObserver.currentTrophies else trophyObserver.currentWins
        Log.i(TAG, "state is lobby, starting game. brawler=$currentBrawler push=$typeOfPush value=$currentValue")
        val values = mapOf(
            "trophies" to trophyObserver.currentTrophies,
            "wins" to trophyObserver.currentWins,
        )
        val value = values[typeOfPush] ?: trophyObserver.currentTrophies
        val pushUntil = toInt(brawlersPickData[0]["push_until"], 1000)

        if (value >= pushUntil) {
            DiagnosticLog.event("stage.push_target_reached", "brawler=$currentBrawler $typeOfPush=$value target=$pushUntil")
            Log.i(TAG, "Brawler $currentBrawler reached target ($value >= $pushUntil)")
            if (brawlersPickData.size <= 1) {
                Log.w(TAG, "No more brawlers. Stopping.")
                throw IllegalStateException("All brawler push targets reached. Bot idle.")
            }
            brawlersPickData.removeAt(0)
            val next = brawlersPickData[0]
            val nextBrawler = next["brawler"].toString()
            if (toBool(next["automatically_pick"], false)) {
                if (!invocation.isLive) {
                    DiagnosticLog.failure("stage", "brawler_select", "authority_refused")
                    return
                }
                val result = lobbyAutomator.selectBrawler(nextBrawler)
                if (result != "success") {
                    DiagnosticLog.failure("stage", "brawler_select", "selection_not_verified")
                    throw BrawlerSelectionFailedException(result)
                }
                if (result == "success") {
                    trophyObserver.changeTrophies(toInt(next["trophies"], 0))
                    trophyObserver.currentWins = toInt(next["wins"], 0)
                    trophyObserver.winStreak = toInt(next["win_streak"], 0)
                }
            } else {
                trophyObserver.changeTrophies(toInt(next["trophies"], 0))
                trophyObserver.currentWins = toInt(next["wins"], 0)
                trophyObserver.winStreak = toInt(next["win_streak"], 0)
                Log.i(TAG, "Next brawler is in manual mode, waiting 10 seconds to let user switch.")
                if (!invocation.await(10_000)) return
            }
        }

        windowController.releaseMovement()
        windowController.guardedNavigationPress("proceed", 20L, invocation.action("lobby"))
        PylaLog.p(TAG, "Pressed to start a match")
        BotStatus.action("Pressed PLAY (proceed)")
        invocation.await(2_000)
    }

    private fun findCanisterBlobs(): List<Pair<Double, Double>> {
        val mat = PylaUtils.frameToMat(windowController.screenshot())
        val h = mat.rows()
        val hsv = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(mat, hsv, org.opencv.imgproc.Imgproc.COLOR_RGB2HSV)
        mat.release()
        val mask = Mat()
        org.opencv.core.Core.inRange(hsv,
            org.opencv.core.Scalar(40.0, 120.0, 120.0),
            org.opencv.core.Scalar(75.0, 255.0, 255.0), mask)
        hsv.release()
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        org.opencv.imgproc.Imgproc.findContours(mask, contours, hierarchy,
            org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
            org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        mask.release()
        val minArea = (h * 0.08) * (h * 0.08)
        val blobs = contours.mapNotNull { c ->
            val area = org.opencv.imgproc.Imgproc.contourArea(c)
            val r = org.opencv.imgproc.Imgproc.boundingRect(c)
            val cy = r.y + r.height / 2.0
            if (area < minArea || cy < h * 0.35 || cy > h * 0.95) null
            else (r.x + r.width / 2.0) to cy
        }.sortedBy { it.first }
        contours.forEach { it.release() }
        return blobs
    }

    private fun clickNanoNoodles(invocation: Invocation, state: String) {
        Log.i(TAG, "Nano noodles screen, picking three noodles")
        BotStatus.action("Nano noodles: picking rewards")
        val blobs = findCanisterBlobs()
        Log.i(TAG, "Nano noodles: ${blobs.size} canisters visible at ${blobs.map { "(${it.first.toInt()},${it.second.toInt()})" }}")
        if (blobs.size < 3) {
            val hr = com.pyla.ai.capture.UiLayout.unit(windowController.width, windowController.height).toFloat()
            val cx = windowController.width / 2f
            val y = com.pyla.ai.capture.UiLayout.y(740.0, windowController.width, windowController.height).toFloat()
            val offset = 330 * hr
            for (x in listOf(cx, cx + offset, cx - offset)) {
                windowController.guardedNavigationTap(x, y, invocation.action(state))
                if (!invocation.await(500)) return
            }
        } else {
            val mid = blobs.size / 2
            val order = listOf(
                blobs[mid],
                blobs[(mid + 1).coerceAtMost(blobs.size - 1)],
                blobs[(mid - 1).coerceAtLeast(0)],
            )
            for (b in order) {
                Log.i(TAG, "Clicking canister at (${b.first.toInt()},${b.second.toInt()})")
                windowController.guardedNavigationTap(b.first.toFloat(), b.second.toFloat(), invocation.action(state))
                if (!invocation.await(600)) return
            }
        }
        if (!invocation.await(1_200)) return
        val remaining = findCanisterBlobs()
        val target = if (remaining.isNotEmpty()) remaining[remaining.size / 2]
        else blobs.getOrNull(blobs.size / 2)
        if (target != null) {
            Log.i(TAG, "Nano noodles: clicking the middle to open rewards (${target.first.toInt()},${target.second.toInt()})")
            BotStatus.action("Nano noodles: opening rewards")
            windowController.guardedNavigationTap(target.first.toFloat(), target.second.toFloat(), invocation.action(state))
            if (!invocation.await(900)) return
            windowController.guardedNavigationTap(target.first.toFloat(), target.second.toFloat(), invocation.action(state))
        }
        invocation.await(1_200)
    }

    private fun handlePrestige(invocation: Invocation) {
        windowController.guardedNavigationPress("continue_or_equip", 20L, invocation.action("prestige_milestone"))
        val entry = brawlersPickData.firstOrNull() ?: return
        if (entry["type"].toString() != "trophies") return
        val floor = when {
            trophyObserver.currentTrophies < 1000 -> 1000
            trophyObserver.currentTrophies < 2000 -> 2000
            else -> return
        }
        Log.i(TAG, "Prestige screen reached: syncing trophies ${trophyObserver.currentTrophies} -> $floor")
        BotStatus.action("Prestige reached, trophies synced to $floor")
        trophyObserver.changeTrophies(floor)
        entry["trophies"] = floor
        entry["win_streak"] = trophyObserver.winStreak
        try {
            BotEngine.saveBrawlerData(brawlersPickData)
        } catch (t: Throwable) {
            DiagnosticLog.failure("stage", "prestige_save", "queue_write_threw", t)
            Log.w(TAG, "queue save: ${t.message}")
        }
    }

    private fun currentStateFromScreen(): String {
        val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()
        val captured = windowController.screenshot()
        val mat = PylaUtils.frameToMat(captured)
        return try { StateFinder.getState(mat, chaosGeneration, captured.capturedAtMs) } finally { mat.release() }
    }

    private fun endGame(invocation: Invocation, stateName: String) {
        var currentState = currentStateFromScreen()
        var buttonPressed = false
        var parsedResult: ParsedGameResult? = null
        val endScreenStartMs = android.os.SystemClock.elapsedRealtime()
        while (currentState.startsWith("end") &&
            android.os.SystemClock.elapsedRealtime() - endScreenStartMs < END_SCREEN_TIMEOUT_MS) {
            if (nowSec() - timeSinceLastStatChange > 25) {
                val rawResult = if (currentState.startsWith("end_")) currentState.removePrefix("end_") else currentState
                parsedResult = trophyObserver.parseGameResult(rawResult)
                trophyObserver.addTrophies(parsedResult, brawlersPickData[0]["brawler"].toString())
                trophyObserver.addWin(parsedResult)
                timeSinceLastStatChange = nowSec()
                val typeToPush = brawlersPickData[0]["type"].toString()
                val v = if (typeToPush == "trophies") trophyObserver.currentTrophies else trophyObserver.currentWins
                brawlersPickData[0][typeToPush] = v
                brawlersPickData[0]["win_streak"] = trophyObserver.winStreak
                try {
                    BotEngine.saveBrawlerData(brawlersPickData)
                } catch (t: Throwable) {
                    DiagnosticLog.failure("stage", "end_save", "queue_write_threw", t)
                    Log.w(TAG, "queue save: ${t.message}")
                }
            }
            if (!buttonPressed && playAgainOnWin && parsedResult != null &&
                parsedResult.result == MatchResultKind.VICTORY) {
                windowController.guardedNavigationPress("play_again", 20L, invocation.action(currentState))
                buttonPressed = true
            } else {
                Log.i(TAG, "Game has ended, proceeding")
                BotStatus.action("End screen ($currentState), pressing proceed")
                windowController.guardedNavigationPress("proceed", 20L, invocation.action(currentState))
            }
            // Cancellation-aware wait: a Pause/Stop during the end screen aborts the whole invocation
            // and must NOT fall through to a recovery relaunch.
            if (!invocation.await(3_000)) return
            currentState = currentStateFromScreen()
        }

        val endScreenTimedOut =
            android.os.SystemClock.elapsedRealtime() - endScreenStartMs >= END_SCREEN_TIMEOUT_MS
        if (playAgainOnWin && parsedResult?.result == MatchResultKind.VICTORY) {
            handOffToGameplayVerifier()
        } else if (endScreenTimedOut && currentState.startsWith("end")) {
            // PC parity: the end screen never went away (frozen game / missed clicks), restart the
            // game so the bot can never get stuck here. Recovery is allowed only while THIS invocation
            // is still live (same session/state) with the current verified end-screen context.
            DiagnosticLog.failure("stage", "end_screen", "timeout")
            Log.w(TAG, "End screen timeout reached, restarting the game.")
            relaunchGame(invocation)
        }
        Log.i(TAG, "Game has ended, current=$currentState")
    }

    /** Return observation to the main loop; only Play's fresh player evidence may admit Match. */
    private fun handOffToGameplayVerifier() {
        windowController.releaseMovement()
        BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.AWAIT_PLAYER, false)
        DiagnosticLog.event("stage.play_again_handoff", "verifier=gameplay_evidence relaunch=false")
    }

    private fun relaunchGame(invocation: Invocation) {
        // The relaunch side effect is a navigation-class action: it may only run while the bound
        // lifecycle invocation is still live (same session, not Pause/Stop/closed).
        if (closed || !invocation.isLive) {
            DiagnosticLog.failure("stage", "relaunch", "authority_refused")
            return
        }
        try {
            val pkg = GameLauncher.gamePackage()
            BotStatus.action("Restarting Brawl Stars")
            GameLauncher.launch(com.pyla.ai.PylaApp.ctx(), pkg)
        } catch (t: Throwable) {
            DiagnosticLog.failure("stage", "relaunch", "launch_threw", t)
            Log.w(TAG, "game relaunch failed: ${t.message}")
        }
        invocation.await(2_000)
    }


    private fun clickStarDrop(invocation: Invocation, state: String) {
        val dropType = state.removePrefix("star_drop_")
        Log.i(TAG, "Handling star drop: $dropType")
        BotStatus.action("Opening star drop ($dropType)")
        if (dropType in setOf("angelic", "demonic", "starr_nova")) {
            windowController.guardedNavigationPress("proceed", 8_000L, invocation.action(state))
        } else {
            repeat(8) {
                if (!invocation.isLive) return
                val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()
                val captured = windowController.screenshot()
                val actual = PylaUtils.frameToMat(captured)
                val observed = try { StateFinder.getState(actual, chaosGeneration, captured.capturedAtMs) } finally { actual.release() }
                DiagnosticLog.sample("navigation.reward_image", "expected=$state observed=$observed")
                if (observed != state) {
                    // A newer image overrules the slower cached state, including already queued taps.
                    invalidateNavigation()
                    DiagnosticLog.failure("stage", "star_drop", "observed_mismatch")
                    return
                }
                windowController.guardedNavigationPress("proceed", 50L, invocation.action(state))
                invocation.await(100)
            }
        }
    }

    /**
     * Upper-left menu BACK for `shop` / `brawler_selection`.
     *
     * The cached classifier state alone never authorises this tap: that is exactly how the 3.0.4
     * Trio follower lost authority and tapped BACK eleven times inside a live match. Two distinct
     * fresh captures are re-classified here, `brawler_selection` additionally needs its independent
     * second menu control, and any visible in-match HUD control denies the pair outright. The
     * granted ticket is re-validated at queued dispatch against live authority, the cached state,
     * capture geometry and freshness.
     */
    private fun quitShop(invocation: Invocation, state: String) {
        val geometry = com.pyla.ai.capture.InputCoordinates.geometryGeneration
        var ticket: MenuBackAdmission.Ticket? = null
        var frameWidth = 0
        var frameHeight = 0
        for (attempt in 0 until MENU_BACK_RECHECK_FRAMES) {
            if (!invocation.isLive) break
            val frame = windowController.screenshot()
            if (attempt > 0 && (frame.width != frameWidth || frame.height != frameHeight)) break
            frameWidth = frame.width
            frameHeight = frame.height
            val mat = PylaUtils.frameToMat(frame)
            val observation = try {
                val observed = StateFinder.getStateObservation(mat, capturedAtMs = frame.capturedAtMs)
                MenuBackAdmission.Observation(
                    state = observed.state,
                    menuControlPresent = observed.identity.menuControlPresent,
                    independentControlPresent = observed.identity.confirmed,
                    gameplayHudControl = observed.identity.gameplayControlPresent,
                    capturedAtMs = frame.capturedAtMs,
                    width = frame.width,
                    height = frame.height,
                )
            } finally {
                mat.release()
            }
            ticket = menuBackGate.observe(observation, android.os.SystemClock.elapsedRealtime())
            if (attempt + 1 < MENU_BACK_RECHECK_FRAMES &&
                !invocation.await(MENU_BACK_RECHECK_STEP_MS)
            ) break
        }
        val granted = ticket
        if (granted == null || granted.state != state) {
            DiagnosticLog.sample(
                "navigation.denied",
                "phase=menu_back reason=menu_identity_unconfirmed state=$state",
                10_000,
            )
            return
        }
        val guard = {
            invocation.isLive && !closed && navigationAllowed() && getState() == state &&
                com.pyla.ai.capture.InputCoordinates.geometryGeneration == geometry &&
                com.pyla.ai.capture.InputCoordinates.captureWidth == frameWidth &&
                com.pyla.ai.capture.InputCoordinates.captureHeight == frameHeight &&
                menuBackGate.allows(granted, android.os.SystemClock.elapsedRealtime())
        }
        if (!guard()) {
            DiagnosticLog.sample(
                "navigation.denied",
                "phase=menu_back reason=evidence_or_authority state=$state",
                10_000,
            )
            return
        }
        DiagnosticLog.event(
            "navigation.menu_back",
            "state=$state reason=confirmed_menu_identity attempt=${granted.serial}",
        )
        windowController.guardedNavigationTap(
            100f * com.pyla.ai.capture.UiLayout.unit(windowController.width, windowController.height).toFloat(),
            60f * com.pyla.ai.capture.UiLayout.unit(windowController.width, windowController.height).toFloat(),
            guard,
        )
        invocation.await(1_000)
    }

    private fun closePopUp(invocation: Invocation) {
        val screenshot = windowController.screenshot()
        if (closePopupIcon == null) {

            val path = PylaConfig.resolve("images/states/close_popup.png").absolutePath
            val raw = Imgcodecs.imread(path)
            if (raw.empty()) {
                DiagnosticLog.failure("stage", "popup_close", "template_missing")
                Log.w(TAG, "close_popup.png missing")
                return
            }
            val sf = windowController.scaleFactor.toDouble()
            val resized = Mat()
            org.opencv.imgproc.Imgproc.resize(raw, resized, org.opencv.core.Size(raw.cols() * sf, raw.rows() * sf))
            raw.release()
            val gray = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(resized, gray, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
            resized.release()
            closePopupIcon = gray
        }
        val template = closePopupIcon ?: return
        val imageMat = PylaUtils.frameToMat(screenshot)
        val imageGray = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(imageMat, imageGray, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY)
        imageMat.release()
        if (imageGray.cols() < template.cols() || imageGray.rows() < template.rows()) {
            DiagnosticLog.failure("stage", "popup_close", "frame_smaller_than_template")
            imageGray.release(); return
        }
        val result = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.matchTemplate(
            imageGray, template,
            result,
            org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED,
        )
        val mmr = org.opencv.core.Core.minMaxLoc(result)
        result.release(); imageGray.release()
        if (mmr.maxVal >= 0.8) {
            val cx = mmr.maxLoc.x + template.cols() / 2
            val cy = mmr.maxLoc.y + template.rows() / 2
            windowController.guardedNavigationTap(cx.toFloat(), cy.toFloat(), invocation.action("popup"))
        }
    }

    fun close() {
        closed = true
        navigationAuthority.close()
        invalidateNavigation()
        closePopupIcon?.release()
        closePopupIcon = null
    }

    private fun toInt(x: Any?, default: Int = 0): Int = when (x) {
        is Number -> x.toInt()
        is String -> x.toIntOrNull() ?: default
        null -> default
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
