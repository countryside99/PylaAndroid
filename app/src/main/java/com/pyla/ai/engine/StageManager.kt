package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import kotlin.concurrent.thread

class StageManager(
    var brawlersPickData: MutableList<MutableMap<String, Any>>,
    val lobbyAutomator: LobbyAutomation,
    val windowController: WindowController,
    val getState: () -> String?,
    val playstyleInfo: Map<String, Any>,
) {
    companion object { private const val TAG = "PylaStage" }

    val trophyObserver = TrophyObserver()

    private val playAgainOnWin: Boolean =
        PylaConfig.load("cfg/bot_config.toml").getString("play_again_on_win", "no").toLowerCase() == "yes"

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
        PylaLog.p(TAG, "doState: $state")
        BotStatus.action("doState: $state")
        when (state) {
            "shop", "brawler_selection" -> quitShop()
            "popup" -> closePopUp()
            "match", "match_making" -> {}
            "lobby" -> startGame()
            "star_drop_regular", "star_drop_angelic", "star_drop_demonic", "star_drop_starr_nova" ->
                clickStarDrop(state.removePrefix("star_drop_"))
            "trophy_reward" -> windowController.press("proceed")
            "prestige_milestone" -> handlePrestige()
            "nano_noodles" -> clickNanoNoodles()
            "end_victory", "end_defeat", "end_draw", "end_trio_showdown_0", "end_trio_showdown_1",
            "end_trio_showdown_2", "end_trio_showdown_3" -> endGame(state)
        }
    }

    fun startGame() {
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
            Log.i(TAG, "Brawler $currentBrawler reached target ($value >= $pushUntil)")
            if (brawlersPickData.size <= 1) {
                Log.w(TAG, "No more brawlers. Stopping.")
                throw IllegalStateException("All brawler push targets reached. Bot idle.")
            }
            brawlersPickData.removeAt(0)
            val next = brawlersPickData[0]
            val nextBrawler = next["brawler"].toString()
            if (toBool(next["automatically_pick"], false)) {
                val result = lobbyAutomator.selectBrawler(nextBrawler)
                if (result == "aborted" || result == "stuck") return
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
                try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
            }
        }

        windowController.releaseMovement()
        windowController.press("proceed")
        PylaLog.p(TAG, "Pressed to start a match")
        BotStatus.action("Pressed PLAY (proceed)")
        try { Thread.sleep(2000) } catch (_: InterruptedException) {}
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
        org.opencv.imgproc.Imgproc.findContours(mask, contours, Mat(),
            org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
            org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE)
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

    private fun clickNanoNoodles() {
        Log.i(TAG, "Nano noodles screen, picking three noodles")
        BotStatus.action("Nano noodles: picking rewards")
        val blobs = findCanisterBlobs()
        Log.i(TAG, "Nano noodles: ${blobs.size} canisters visible at ${blobs.map { "(${it.first.toInt()},${it.second.toInt()})" }}")
        if (blobs.size < 3) {
            val hr = windowController.heightRatio
            val cx = windowController.width / 2f
            val y = 740 * hr
            val offset = 330 * hr
            for (x in listOf(cx, cx + offset, cx - offset)) {
                windowController.click(x, y)
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
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
                windowController.click(b.first.toFloat(), b.second.toFloat())
                try { Thread.sleep(600) } catch (_: InterruptedException) {}
            }
        }
        try { Thread.sleep(1200) } catch (_: InterruptedException) {}
        val remaining = findCanisterBlobs()
        val target = if (remaining.isNotEmpty()) remaining[remaining.size / 2]
        else blobs.getOrNull(blobs.size / 2)
        if (target != null) {
            Log.i(TAG, "Nano noodles: clicking the middle to open rewards (${target.first.toInt()},${target.second.toInt()})")
            BotStatus.action("Nano noodles: opening rewards")
            windowController.click(target.first.toFloat(), target.second.toFloat())
            try { Thread.sleep(900) } catch (_: InterruptedException) {}
            windowController.click(target.first.toFloat(), target.second.toFloat())
        }
        try { Thread.sleep(1200) } catch (_: InterruptedException) {}
    }

    private fun handlePrestige() {
        windowController.press("continue_or_equip")
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
        try { BotEngine.saveBrawlerData(brawlersPickData) } catch (t: Throwable) { Log.w(TAG, "queue save: ${t.message}") }
    }

    private fun currentStateFromScreen(): String {
        val mat = PylaUtils.frameToMat(windowController.screenshot())
        return try { StateFinder.getState(mat) } finally { mat.release() }
    }

    fun endGame(stateName: String) {
        var currentState = currentStateFromScreen()
        var buttonPressed = false
        var parsedResult: ParsedGameResult? = null
        val endScreenTime = nowSec()
        while (currentState.startsWith("end") && nowSec() - endScreenTime < 35) {
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
                try { BotEngine.saveBrawlerData(brawlersPickData) } catch (t: Throwable) { Log.w(TAG, "queue save: ${t.message}") }
            }
            if (!buttonPressed && playAgainOnWin && parsedResult != null &&
                parsedResult.result == MatchResultKind.VICTORY) {
                windowController.press("play_again")
                buttonPressed = true
            } else {
                Log.i(TAG, "Game has ended, proceeding")
                BotStatus.action("End screen ($currentState), pressing proceed")
                windowController.press("proceed")
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            currentState = currentStateFromScreen()
        }

        if (playAgainOnWin && parsedResult?.result == MatchResultKind.VICTORY) {
            Log.i(TAG, "Waiting for match to start...")
            val startWait = nowSec()
            while (nowSec() - startWait < 25) {
                if (currentStateFromScreen() == "match") { Log.i(TAG, "Match started successfully!"); return }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            Log.i(TAG, "Match did not start within 25s, returning to lobby.")
            windowController.press("proceed")
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        }
        Log.i(TAG, "Game has ended, current=$currentState")
    }


    private fun clickStarDrop(dropType: String) {
        Log.i(TAG, "Handling star drop: $dropType")
        BotStatus.action("Opening star drop ($dropType)")
        if (dropType in setOf("angelic", "demonic", "starr_nova")) {
            windowController.press("proceed", delayMs = 8_000)
        } else {
            repeat(8) {
                windowController.press("proceed", delayMs = 50)
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
        }
    }

    fun quitShop() {
        windowController.click(100f * windowController.heightRatio, 60f * windowController.heightRatio)
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
    }

    fun closePopUp() {
        val screenshot = windowController.screenshot()
        if (closePopupIcon == null) {

            val path = PylaConfig.resolve("images/states/close_popup.png").absolutePath
            val raw = Imgcodecs.imread(path)
            if (raw.empty()) { Log.w(TAG, "close_popup.png missing"); return }
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
            windowController.click(cx.toFloat(), cy.toFloat())
        }
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