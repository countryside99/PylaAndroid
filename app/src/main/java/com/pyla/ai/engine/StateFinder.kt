package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.TomlLite
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

object StateFinder {

    private const val TAG = "PylaState"
    private const val ORIG_W = 1920
    private const val ORIG_H = 1080

    /**
     * Correlation floor for an INDEPENDENT menu control. Higher than the 0.7 default used inside a
     * narrow configured region, because this probe searches a wide band where a false match would
     * remove the only corroboration the ambiguous brawler menu has.
     */
    private const val MENU_CONTROL_THRESHOLD = 0.90

    /**
     * Horizontal search slack per side, in 1920x1080 reference pixels. Measured on a 16:9 device:
     * left-anchored result cues sit 46-51 reference pixels left of the phone-authored regions and
     * PLAY AGAIN 36 to the left. 130 also covers a display-cutout inset on the opposite side.
     */
    internal const val REGION_SLACK_X = 130.0

    private val SINGLE_SCALE = doubleArrayOf(1.0)
    private val NARROW_CUE_SCALES = doubleArrayOf(1.0, 1.02)
    private val GENERIC_RESULTS = setOf("victory", "defeat", "draw")

    /** Channel floor for "near white" label/outline pixels. */
    private const val WHITE_FLOOR = 191.0

    /**
     * Measured on a 16:9 device: the visible EXIT scored 0.62-0.67 on three different map
     * backgrounds, every other screen (match, result, lobby) at most 0.36.
     */
    private const val WHITE_SHAPE_THRESHOLD = 0.50

    /** Nominal scale first, so an already clear match costs exactly one correlation as before. */
    private val LEGACY_TEMPLATE_SCALES = doubleArrayOf(1.0, 1.08, 1.04)

    /** A correlation this clear needs no further scale. */
    private const val LEGACY_SCALE_SATISFIED = 0.90

    /** Below this nominal-scale correlation the control is simply not on screen. */
    private const val LEGACY_SCALE_WORTH_RETRY = 0.55

    private val statesPath get() = PylaConfig.resolve("images/states/")
    private val starDropsPath get() = PylaConfig.resolve("images/star_drop_types/")
    private val endResultsPath get() = PylaConfig.resolve("images/end_results/")
    private val brawlerMenuSearchPath get() = File(statesPath, "brawler_menu_search.png").absolutePath

    private val lobbyConfig: TomlLite = PylaConfig.load("cfg/lobby_config.toml")
    private val matchResultRegion: List<Int> = lobbyConfig.getIntArray("template_matching.match_result")
    private val regions: TomlLite = lobbyConfig

    private val starDropFiles: List<String> = run {
        val dir = starDropsPath
        if (!dir.exists()) {
            PylaLog.w(TAG, "star_drop_types/ missing at $dir")
            emptyList()
        } else {
            dir.listFiles().orEmpty().filter { it.name.contains("star_drop", ignoreCase = true) }.map { it.name }
        }
    }

    private val templateCache = HashMap<String, Mat>()
    private val templateMissingLogged = HashSet<String>()

    @Synchronized
    private fun loadTemplate(path: String, w: Int, h: Int, relativeScale: Double = 1.0): Mat {
        val key = "$path|$w|$h|$relativeScale"
        templateCache[key]?.let { return it }
        val file = File(path)
        val videoCue = file.parentFile?.name == "video_state_cues"
        val menuSearchCue = file.parentFile?.name == "states" && file.name == "brawler_menu_search.png"
        if (!videoCue && !menuSearchCue && !file.exists() && path !in templateMissingLogged) {
            PylaLog.w(TAG, "Template file DOES NOT EXIST on disk: $path (cwd=${System.getProperty("user.dir")})")
            templateMissingLogged.add(path)
        }
        val raw = if (videoCue || menuSearchCue) {
            // Read new packaged cues directly. Bumping AssetBundler's global version would
            // overwrite the user's retained configuration on an in-place update.
            try {
                val asset = if (videoCue) "pyla/images/video_state_cues/${file.name}"
                    else "pyla/images/states/brawler_menu_search.png"
                val bytes = com.pyla.ai.PylaApp.ctx().assets.open(asset).use { it.readBytes() }
                val encoded = org.opencv.core.MatOfByte(*bytes)
                try { org.opencv.imgcodecs.Imgcodecs.imdecode(encoded, org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR) }
                finally { encoded.release() }
            } catch (_: Exception) { Mat() }
        } else org.opencv.imgcodecs.Imgcodecs.imread(path)
        if (raw.empty()) {
            if (path !in templateMissingLogged) {
                PylaLog.w(TAG, "Template imread returned empty: $path exists=${file.exists()} size=${file.length()}")
                templateMissingLogged.add(path)
            }
            templateCache[key] = raw
            return raw
        }
        if (path !in templateMissingLogged) PylaLog.p(TAG, "Loaded template $path (${raw.cols()}x${raw.rows()})")
        val hr = relativeScale * com.pyla.ai.capture.UiLayout.unit(w, h, (if (videoCue) VideoScreenStateCues.REFERENCE_HEIGHT else ORIG_H).toDouble())
        val resized = Mat()
        Imgproc.resize(raw, resized, Size(raw.cols() * hr, raw.rows() * hr))
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        raw.release(); resized.release()
        templateCache[key] = rgb
        return rgb
    }

    private fun isTemplateInRegion(
        image: Mat, templatePath: String, region: List<Int>, threshold: Double = 0.7, slackX: Double = 0.0,
    ): Boolean {
        if (region.size < 4) return false
        val curH = image.rows(); val curW = image.cols()
        val wr = curW.toDouble() / ORIG_W; val hr = com.pyla.ai.capture.UiLayout.unit(curW, curH)
        val baseCenter = region[0] + region[2] / 2.0
        val anchoredX = when {
            baseCenter < ORIG_W / 3.0 -> region[0] * hr
            baseCenter > ORIG_W * 2 / 3.0 -> curW - (ORIG_W - region[0]) * hr
            else -> curW / 2.0 + (region[0] - ORIG_W / 2.0) * hr
        }
        val propX = region[0] * wr
        val pad = 12 * hr
        val ny = com.pyla.ai.capture.UiLayout.top(region[1].toDouble(), region[3].toDouble(), curW, curH).toInt()
        val nh = (region[3] * hr).toInt()
        if (region[2] <= 0 || nh <= 0) return false
        // The configured regions were authored on one wide phone, where the union of the
        // proportional and the edge-anchored placement happens to leave slack. On a 16:9 screen
        // both placements coincide and the region collapses to its bare width, while the game
        // draws edge-anchored controls without that phone's safe-area inset. A fixed horizontal
        // slack covers both layouts. Only the multi-cue video states request it: a single legacy
        // 0.7 template searched over a wider area was measured to produce a false match.
        val slack = slackX * hr
        val x0 = (minOf(propX, anchoredX) - pad - slack).toInt().coerceIn(0, curW)
        val x1 = (maxOf(propX + region[2] * wr, anchoredX + region[2] * hr) + pad + slack).toInt().coerceIn(0, curW)
        val y0 = ny.coerceIn(0, curH)
        val y1 = (ny + nh).coerceIn(0, curH)
        if (x1 - x0 <= 0 || y1 - y0 <= 0) return false
        val crop = Mat(image, Rect(x0, y0, x1 - x0, y1 - y0))
        // The legacy 1080p control templates predate the current game UI: on a real 16:9 device
        // the lobby menu button correlates 0.76 at the nominal scale (flaky against 0.70) but
        // 0.98 at 1.08x. The same control is therefore tried at a few nearby scales and the best
        // correlation counts. Video cues were measured to match at exactly 1.00 and stay single.
        val videoCue = File(templatePath).parentFile?.name == "video_state_cues"
        // On frames narrower than 16:9 the width-scaled UI lands between template pixel sizes:
        // the true "Brawlers left" cue measured 0.83 at the nominal size and 0.89 at 1.02x on a
        // 4:3 frame, so the elimination screen was never recognised there. Wide frames keep the
        // single nominal scale.
        val scales = when {
            !videoCue -> LEGACY_TEMPLATE_SCALES
            com.pyla.ai.capture.UiLayout.isNarrow(curW, curH) -> NARROW_CUE_SCALES
            else -> SINGLE_SCALE
        }
        var score = Double.NEGATIVE_INFINITY
        var usable = false
        for (relativeScale in scales) {
            val template = loadTemplate(templatePath, curW, curH, relativeScale)
            if (template.empty()) break
            if (crop.cols() < template.cols() || crop.rows() < template.rows()) continue
            val result = Mat()
            Imgproc.matchTemplate(crop, template, result, Imgproc.TM_CCOEFF_NORMED)
            val candidate = org.opencv.core.Core.minMaxLoc(result).maxVal
            result.release()
            usable = true
            if (candidate > score) score = candidate
            // Unrelated screens stay below 0.5 at the nominal scale; only a near miss is worth
            // the extra scales. This keeps the per-frame cost on match frames unchanged.
            if (score > LEGACY_SCALE_SATISFIED || score < LEGACY_SCALE_WORTH_RETRY) break
        }
        crop.release()
        if (!usable) return false
        val passed = score > threshold
        if (!passed && score > 0.4 && File(templatePath).name in DEBUG_NEAR_MISS_FILES) {
            PylaLog.p(TAG, "near miss: ${File(templatePath).name} score=%.3f thresh=%.2f".format(score, threshold))
        }
        return passed
    }

    private val DEBUG_NEAR_MISS_FILES = setOf("lobby_menu.png", "exit_match_making.png", "close_popup.png")

    private val showdownPlaceTemplates: Map<Int, List<String>> = mapOf(
        0 to listOf("1st.png"),
        1 to listOf("2nd.png"),
        2 to listOf("3rd.png", "3rd_alt.png"),
        3 to listOf("4th.png"),
    )

    private fun findGameResult(screenshot: Mat): String? {
        for ((place, files) in showdownPlaceTemplates) {
            for (f in files) {
                if (isTemplateInRegion(screenshot, File(endResultsPath, f).absolutePath, matchResultRegion, 0.9))
                    return "trio_showdown_$place"
            }
        }
        if (isTemplateInRegion(screenshot, File(endResultsPath, "victory.png").absolutePath, matchResultRegion)) return "victory"
        if (isTemplateInRegion(screenshot, File(endResultsPath, "defeat.png").absolutePath, matchResultRegion)) return "defeat"
        if (isTemplateInRegion(screenshot, File(endResultsPath, "draw.png").absolutePath, matchResultRegion)) return "draw"
        return null
    }

    private fun inShop(image: Mat) = isTemplateInRegion(image, File(statesPath, "powerpoint.png").absolutePath, regions.getIntArray("template_matching.powerpoint"))
    private fun inBrawlerSelection(image: Mat) = isTemplateInRegion(image, File(statesPath, "brawler_menu_heart.png").absolutePath, regions.getIntArray("template_matching.brawler_menu_heart"))
    private fun inOfferPopup(image: Mat) = isTemplateInRegion(image, File(statesPath, "close_popup.png").absolutePath, regions.getIntArray("template_matching.close_popup"))
    private fun inLobby(image: Mat) = isTemplateInRegion(image, File(statesPath, "lobby_menu.png").absolutePath, regions.getIntArray("template_matching.lobby_menu"))
    private fun inTrophyReward(image: Mat) = isTemplateInRegion(image, File(statesPath, "trophies_screen.png").absolutePath, regions.getIntArray("template_matching.trophies_screen"))
    private fun inBrawlPass(image: Mat) = isTemplateInRegion(image, File(statesPath, "brawl_pass_house.png").absolutePath, regions.getIntArray("template_matching.brawl_pass_house"))
    private fun inStarRoad(image: Mat) = isTemplateInRegion(image, File(statesPath, "go_back_arrow.png").absolutePath, regions.getIntArray("template_matching.go_back_arrow"))
    private fun inMatchMaking(image: Mat) = isTemplateInRegion(image, File(statesPath, "exit_match_making.png").absolutePath, regions.getIntArray("template_matching.exit_match_making"))
    private fun inPrestigeMilestone(image: Mat) = isTemplateInRegion(image, File(statesPath, "prestige_continue.png").absolutePath, regions.getIntArray("template_matching.prestige_continue"))
    private fun inNanoNoodles(image: Mat): Boolean {
        if (!isTemplateInRegion(image, File(statesPath, "nano_noodles.png").absolutePath, regions.getIntArray("template_matching.nano_noodles"))) return false
        return hasCanisterRow(image)
    }

    private fun hasCanisterRow(image: Mat): Boolean {
        val h = image.rows(); val w = image.cols()
        if (h < 100 || w < 100) return false
        val bandY = (h * 0.40).toInt()
        val bandH = (h * 0.55).toInt().coerceAtMost(h - bandY)
        val band = Mat(image, Rect(0, bandY, w, bandH))
        val hsv = Mat()
        Imgproc.cvtColor(band, hsv, Imgproc.COLOR_RGB2HSV)
        band.release()
        val mask = Mat()
        org.opencv.core.Core.inRange(hsv,
            org.opencv.core.Scalar(40.0, 120.0, 120.0),
            org.opencv.core.Scalar(75.0, 255.0, 255.0), mask)
        hsv.release()
        val minBlobArea = (h * 0.08) * (h * 0.08)
        if (org.opencv.core.Core.countNonZero(mask) < minBlobArea * 3) { mask.release(); return false }
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        mask.release()
        val bigBlobs = contours.count { Imgproc.contourArea(it) >= minBlobArea }
        contours.forEach { it.release() }
        if (bigBlobs >= 3) {
            PylaLog.p(TAG, "Nano noodles confirmed by canister row ($bigBlobs blobs)")
            return true
        }
        return false
    }

    private fun inStarDrop(image: Mat): String? {
        for (f in starDropFiles) {
            val low = f.lowercase()
            if (isTemplateInRegion(image, File(starDropsPath, f).absolutePath, regions.getIntArray("template_matching.star_drop"))) {
                return when {
                    "angelic" in low -> "angelic"
                    "demonic" in low -> "demonic"
                    "starr_nova" in low -> "starr_nova"
                    else -> "regular"
                }
            }
        }
        return null
    }

    /** Locate the actual bottom-centre elimination EXIT, not the unrelated result-screen EXIT. */
    fun findDefeatedExit(screenshot: Mat): org.opencv.core.Point? {
        if (getState(screenshot) != "defeated") return null
        val w = screenshot.cols(); val h = screenshot.rows()
        if (w <= 0 || h <= 0) return null
        val reference = VideoScreenStateCues.REFERENCE_HEIGHT.toDouble()
        val scale = com.pyla.ai.capture.UiLayout.unit(w, h, reference)
        // Reference ROI is centred on the observed control and follows the game's UI scale.
        return findEnabledControl(screenshot, "defeated_exit",
            (w / 2.0 - 110.0 * scale).toInt(), com.pyla.ai.capture.UiLayout.y(500.0, w, h, reference).toInt(),
            (w / 2.0 + 150.0 * scale).toInt(), com.pyla.ai.capture.UiLayout.y(585.0, w, h, reference).toInt(), translucent = true)
    }

    /**
     * Locate the enabled EXIT of the ranked result screen (Rank / PLAY AGAIN / EXIT). The screen
     * itself must already be positively classified from its four independent cues.
     */
    fun findResultExit(screenshot: Mat): org.opencv.core.Point? {
        if (getState(screenshot) != "end_ranked") return null
        val w = screenshot.cols(); val h = screenshot.rows()
        if (w <= 0 || h <= 0) return null
        // The control is anchored to the bottom-right corner on every aspect ratio.
        return findEnabledControl(screenshot, "result_exit",
            (w * 0.60).toInt(), (h * 0.80).toInt(), w, h, translucent = false)
    }

    /**
     * Blue CONTINUE of a reward presentation (e.g. "NEW SKIN!" with CONTINUE / EQUIP NOW). It is
     * the neutral way out: nothing is equipped or bought. Measured 0.97 on the control against at
     * most 0.43 on 200 lobby, match, result, drop and dialog frames.
     */
    fun findRewardContinue(screenshot: Mat): org.opencv.core.Point? {
        val w = screenshot.cols(); val h = screenshot.rows()
        if (w <= 0 || h <= 0) return null
        // Horizontally centred control; a narrow band keeps this per-frame check cheap.
        return findEnabledControl(screenshot, "reward_continue",
            (w * 0.30).toInt(), (h * 0.74).toInt(), (w * 0.70).toInt(), (h * 0.96).toInt(), translucent = false)
    }

    private fun findEnabledControl(
        screenshot: Mat, cue: String, rx0: Int, ry0: Int, rx1: Int, ry1: Int, translucent: Boolean,
    ): org.opencv.core.Point? {
        val w = screenshot.cols(); val h = screenshot.rows()
        val x0 = rx0.coerceIn(0, w); val x1 = rx1.coerceIn(0, w)
        val y0 = ry0.coerceIn(0, h); val y1 = ry1.coerceIn(0, h)
        if (x1 <= x0 || y1 <= y0) return null
        val template = loadTemplate(PylaConfig.resolve("images/video_state_cues/$cue.png").absolutePath, w, h)
        if (template.empty() || template.cols() > x1 - x0 || template.rows() > y1 - y0) return null
        val crop = Mat(screenshot, Rect(x0, y0, x1 - x0, y1 - y0))
        val scores = Mat()
        try {
            Imgproc.matchTemplate(crop, template, scores, Imgproc.TM_CCOEFF_NORMED)
            val match = org.opencv.core.Core.minMaxLoc(scores)
            if (match.maxVal.isFinite() && match.maxVal > VideoScreenStateCues.THRESHOLD) {
                val patch = Mat(crop, Rect(match.maxLoc.x.toInt(), match.maxLoc.y.toInt(), template.cols(), template.rows()))
                val enabled = try {
                    // Correlation alone is brightness-invariant: a darkened disabled control must not
                    // become action permission. Keep the real enabled reference's absolute pixels too.
                    val error = org.opencv.core.Core.norm(patch, template, org.opencv.core.Core.NORM_L1) /
                        (template.total() * template.channels())
                    val referenceMean = org.opencv.core.Core.mean(template).`val`.take(3).average()
                    val liveMean = org.opencv.core.Core.mean(patch).`val`.take(3).average()
                    error.isFinite() && error <= 22.0 && liveMean >= referenceMean * 0.85
                } finally { patch.release() }
                if (enabled) return org.opencv.core.Point(x0 + match.maxLoc.x + template.cols() / 2.0,
                    y0 + match.maxLoc.y + template.rows() / 2.0)
            }
            if (!translucent) return null
            // The elimination EXIT is translucent: the map behind it shows through, so its colours
            // only matched the reference on the map the reference was cropped from (0.69-0.82 on
            // another map). Its white label and outline do not depend on the background, and a
            // greyed-out control has no near-white pixels at all, so they are compared instead.
            return whiteShapeMatch(crop, template)?.let {
                org.opencv.core.Point(x0 + it.x + template.cols() / 2.0, y0 + it.y + template.rows() / 2.0)
            }
        } finally { scores.release(); crop.release() }
    }

    /** Best position of the template's near-white pixels inside [crop], or null if not clear. */
    private fun whiteShapeMatch(crop: Mat, template: Mat): org.opencv.core.Point? {
        val cropWhite = Mat(); val templateWhite = Mat(); val scores = Mat()
        try {
            val low = org.opencv.core.Scalar(WHITE_FLOOR, WHITE_FLOOR, WHITE_FLOOR)
            val high = org.opencv.core.Scalar(255.0, 255.0, 255.0)
            org.opencv.core.Core.inRange(crop, low, high, cropWhite)
            org.opencv.core.Core.inRange(template, low, high, templateWhite)
            if (org.opencv.core.Core.countNonZero(templateWhite) == 0) return null
            Imgproc.matchTemplate(cropWhite, templateWhite, scores, Imgproc.TM_CCOEFF_NORMED)
            val match = org.opencv.core.Core.minMaxLoc(scores)
            return if (match.maxVal.isFinite() && match.maxVal > WHITE_SHAPE_THRESHOLD) match.maxLoc else null
        } finally { cropWhite.release(); templateWhite.release(); scores.release() }
    }

    private fun videoScreenState(screenshot: Mat): String? {
        val matches = VideoScreenStateCues.regions.filter { (name, roi) ->
            val path = PylaConfig.resolve("images/video_state_cues/$name.png").absolutePath
            isTemplateInRegion(screenshot, path, roi, VideoScreenStateCues.THRESHOLD, slackX = REGION_SLACK_X)
        }.keys
        return VideoScreenStateCues.classify(matches)
    }

    /**
     * Classifier output plus the menu-identity evidence it was derived from.
     *
     * The state string stays the single API every consumer reads; [identity] is the extra,
     * non-permissive evidence that menu navigation (and match revocation) must consult, because a
     * single-template correlation is not menu identity.
     */
    data class StateObservation(val state: String, val identity: MenuIdentityAdmission.Identity) {
        val menuConfirmed: Boolean get() = identity.confirmed
    }

    /** Neutral identity for states that have no ambiguous menu evidence. */
    private fun neutralIdentity(state: String) = MenuIdentityAdmission.evaluate(
        state = state, primaryControl = false, independentControl = false, gameplayControl = false,
    )

    fun getState(
        screenshot: Mat,
        chaosGeneration: Long? = null,
        capturedAtMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): String = getStateObservation(screenshot, chaosGeneration, capturedAtMs).state

    fun getStateObservation(
        screenshot: Mat,
        chaosGeneration: Long? = null,
        capturedAtMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): StateObservation {
        val result = classifyObservation(screenshot, chaosGeneration, capturedAtMs)
        if (chaosGeneration != null && result.state != "unknown" && !result.state.startsWith("star_drop_"))
            PhoneVisualCues.revokeChaosContinuation(chaosGeneration)
        return result
    }

    /**
     * Independent brawler-menu identity control.
     *
     * The pinned upstream search control corroborates the heart (AND, never upstream's broad OR).
     * Read it directly from this APK so retained installs do not need a global asset overwrite.
     * The header band derives from the user's configured heart region, not guessed coordinates.
     * The old unused task/checkmark asset is deliberately NOT treated as menu-specific evidence.
     */
    private fun hasBrawlerMenuSearchControl(image: Mat): Boolean {
        val region = regions.getIntArray("template_matching.brawler_menu_heart")
        if (region.size < 4 || region[3] <= 0) return false
        val curH = image.rows(); val curW = image.cols()
        if (curH <= 0 || curW <= 0) return false
        val hr = com.pyla.ai.capture.UiLayout.unit(curW, curH)
        val y0 = com.pyla.ai.capture.UiLayout.top(region[1].toDouble(), region[3].toDouble(), curW, curH).toInt().coerceIn(0, curH)
        val bandH = (region[3] * hr).toInt().coerceAtMost(curH - y0)
        if (bandH <= 0) return false
        val template = loadTemplate(brawlerMenuSearchPath, curW, curH)
        if (template.empty()) return false
        if (template.cols() > curW || template.rows() > bandH) return false
        val band = Mat(image, Rect(0, y0, curW, bandH))
        val scores = Mat()
        return try {
            Imgproc.matchTemplate(band, template, scores, Imgproc.TM_CCOEFF_NORMED)
            val match = org.opencv.core.Core.minMaxLoc(scores)
            match.maxVal.isFinite() && match.maxVal >= MENU_CONTROL_THRESHOLD
        } catch (t: Throwable) {
            com.pyla.ai.diagnostics.DiagnosticLog.failure("state", "menu_control", "probe_failed", t)
            false
        } finally {
            band.release(); scores.release()
        }
    }

    /**
     * Positive gameplay evidence from the validated production attack-control recognizer.
     *
     * A visible red attack ring means this exact frame is an in-match HUD frame, so no menu state
     * can be derived from it. Only evaluated inside the ambiguous menu branches, so the cost is
     * paid exactly where the ambiguity exists.
     */
    private fun hasGameplayHudControl(image: Mat): Boolean? = try {
        AttackButtonDetector.detect(image) != null
    } catch (t: Throwable) {
        com.pyla.ai.diagnostics.DiagnosticLog.failure("state", "gameplay_control", "probe_failed", t)
        // Detector failure is neither positive gameplay nor evidence that gameplay is absent.
        // Callers must deny menu admission instead of treating the exception as a negative.
        null
    }

    /**
     * Capture time of the last positively recognised reward screen (drop artwork, trophy road
     * reward, reward CONTINUE or a reveal). A reward is usually followed by further plain "YOU GOT" reveals
     * (pins, icons, ...) that each need one more tap; those carry no control of their own, so
     * they are only accepted as [REWARD_REVEAL] within [REWARD_CHAIN_MS] of such a screen.
     */
    @Volatile private var lastRewardScreenAtMs = 0L
    private const val REWARD_CHAIN_MS = 10_000L
    const val REWARD_REVEAL = "reward_reveal"

    /**
     * "YOU GOT:" heading of a plain reward reveal (pins, icons, ...) in the upper centre of the
     * frame. Measured 0.96 on the heading against at most 0.33 on 210 other frames.
     */
    private fun hasYouGotHeading(screenshot: Mat): Boolean {
        val w = screenshot.cols(); val h = screenshot.rows()
        // The heading is horizontally centred; a narrow band keeps this check cheap.
        val x0 = (w * 0.28).toInt(); val bandW = (w * 0.44).toInt()
        val y0 = (h * 0.08).toInt(); val bandH = (h * 0.32).toInt()
        val template = loadTemplate(PylaConfig.resolve("images/video_state_cues/reward_you_got.png").absolutePath, w, h)
        if (template.empty() || template.cols() > bandW || template.rows() > bandH) return false
        val band = Mat(screenshot, Rect(x0, y0, bandW, bandH)); val scores = Mat()
        return try {
            Imgproc.matchTemplate(band, template, scores, Imgproc.TM_CCOEFF_NORMED)
            val best = org.opencv.core.Core.minMaxLoc(scores).maxVal
            best.isFinite() && best > VideoScreenStateCues.THRESHOLD
        } catch (_: Exception) { false } finally { band.release(); scores.release() }
    }

    /**
     * A reward reveal is accepted (a) by its "YOU GOT:" heading, (b) as a plain single-hue screen
     * right after another reward screen, or (c) as a plain single-hue screen that has stayed on
     * display for [STANDALONE_REVEAL_MS]. (c) covers a bot started, resumed or reconnected in the
     * middle of a reward sequence: without it the bot waited there for good. A reveal has no UI
     * at all in its outer band, so no menu, shop or match frame can satisfy the test.
     */
    private fun isRewardRevealScreen(screenshot: Mat, capturedAtMs: Long): Boolean {
        if (hasYouGotHeading(screenshot)) { plainRevealSinceMs = 0L; return true }
        if (!PhoneVisualCues.isRewardReveal(screenshot)) { plainRevealSinceMs = 0L; return false }
        if (capturedAtMs - lastRewardScreenAtMs in 0..REWARD_CHAIN_MS) { plainRevealSinceMs = 0L; return true }
        val since = plainRevealSinceMs
        if (since == 0L || capturedAtMs < since) { plainRevealSinceMs = capturedAtMs; return false }
        return capturedAtMs - since >= STANDALONE_REVEAL_MS
    }

    @Volatile private var plainRevealSinceMs = 0L
    private const val STANDALONE_REVEAL_MS = 3_000L

    private fun classifyObservation(screenshot: Mat, chaosGeneration: Long?, capturedAtMs: Long): StateObservation {
        val observation = classifyScreen(screenshot, chaosGeneration, capturedAtMs)
        val state = observation.state
        if (state != "unknown") unknownSinceMs = 0L
        if (state.startsWith("star_drop_") || state == "reward_continue" || state == "trophy_reward") {
            lastRewardScreenAtMs = capturedAtMs
        } else if (state == "unknown" && isRewardRevealScreen(screenshot, capturedAtMs)) {
            lastRewardScreenAtMs = capturedAtMs
            unknownSinceMs = 0L
            PylaLog.p(TAG, "State: $REWARD_REVEAL")
            return StateObservation(REWARD_REVEAL, neutralIdentity(REWARD_REVEAL))
        } else if (state == "unknown") {
            // Multiplying drops show animated screens without heading or plain background; see
            // RewardChain. This does not refresh the chain, so it always ends by itself.
            val since = unknownSinceMs
            if (since == 0L || capturedAtMs < since) unknownSinceMs = capturedAtMs
            else if (RewardChain.pending(capturedAtMs, lastRewardScreenAtMs, since)) {
                PylaLog.p(TAG, "State: $REWARD_REVEAL (reward sequence)")
                return StateObservation(REWARD_REVEAL, neutralIdentity(REWARD_REVEAL))
            }
        } else {
            lastRewardScreenAtMs = 0L // any other positively known screen ends the chain
        }
        return observation
    }

    @Volatile private var unknownSinceMs = 0L

    private fun classifyScreen(screenshot: Mat, chaosGeneration: Long?, capturedAtMs: Long): StateObservation {
        val gameResult = findGameResult(screenshot)
        // The loose 0.70 word templates can fire on the ranked result heading: "Rank: 5"
        // correlated exactly 0.70 with DRAW and was booked as a draw. That screen has its own
        // four independent cues, which take precedence over a generic word match.
        val rankedScreen = if (gameResult in GENERIC_RESULTS) videoScreenState(screenshot)
            ?.takeIf { it == "end_ranked" || it == "result_preview" } else null
        rankedScreen?.let { return StateObservation(it, neutralIdentity(it)) }
        gameResult?.let {
            PylaLog.p(TAG, "State: end_$it (${screenshot.cols()}x${screenshot.rows()})")
            return StateObservation("end_$it", neutralIdentity("end_$it"))
        }
        videoScreenState(screenshot)?.let { return StateObservation(it, neutralIdentity(it)) }
        if (findRewardContinue(screenshot) != null) {
            PylaLog.p(TAG, "State: reward_continue")
            return StateObservation("reward_continue", neutralIdentity("reward_continue"))
        }
        val chaosDrop = PhoneVisualCues.isChaosDrop(screenshot, chaosGeneration = chaosGeneration, capturedAtMs = capturedAtMs)
        if (chaosDrop)
            return StateObservation("star_drop_regular", neutralIdentity("star_drop_regular"))
        if (inNanoNoodles(screenshot)) { PylaLog.p(TAG, "State: nano_noodles"); return StateObservation("nano_noodles", neutralIdentity("nano_noodles")) }
        inStarDrop(screenshot)?.let {
            PylaLog.p(TAG, "State: star_drop_$it")
            return StateObservation("star_drop_$it", neutralIdentity("star_drop_$it"))
        }
        if (inTrophyReward(screenshot)) { PylaLog.p(TAG, "State: trophy_reward"); return StateObservation("trophy_reward", neutralIdentity("trophy_reward")) }
        if (inPrestigeMilestone(screenshot)) { PylaLog.p(TAG, "State: prestige_milestone"); return StateObservation("prestige_milestone", neutralIdentity("prestige_milestone")) }
        if (inLobby(screenshot)) { PylaLog.p(TAG, "State: lobby"); return StateObservation("lobby", neutralIdentity("lobby")) }
        if (inMatchMaking(screenshot)) { PylaLog.p(TAG, "State: match_making"); return StateObservation("match_making", neutralIdentity("match_making")) }
        if (inBrawlerSelection(screenshot)) {
            // Heart alone is not menu identity: it is corroborated by a second independent menu
            // control, and any visible in-match HUD control vetoes the menu classification.
            val identity = MenuIdentityAdmission.evaluate(
                state = "brawler_selection",
                primaryControl = true,
                independentControl = hasBrawlerMenuSearchControl(screenshot),
                gameplayControl = hasGameplayHudControl(screenshot)
                    ?: return StateObservation("unknown", neutralIdentity("unknown")),
            )
            val published = MenuIdentityAdmission.publicState(identity)
            if (published == "brawler_selection") {
                PylaLog.p(TAG, "State: brawler_selection (${identity.level})")
            } else {
                PylaLog.w(TAG, "brawler_selection evidence rejected (${identity.level}: ${identity.reason})")
            }
            return StateObservation(published, identity)
        }
        if (inShop(screenshot)) {
            val identity = MenuIdentityAdmission.evaluate(
                state = "shop",
                primaryControl = true,
                independentControl = false,
                gameplayControl = hasGameplayHudControl(screenshot)
                    ?: return StateObservation("unknown", neutralIdentity("unknown")),
            )
            val published = MenuIdentityAdmission.publicState(identity)
            if (published == "shop") {
                PylaLog.p(TAG, "State: shop (${identity.level})")
            } else {
                PylaLog.w(TAG, "shop evidence rejected (${identity.level}: ${identity.reason})")
            }
            return StateObservation(published, identity)
        }
        if (inOfferPopup(screenshot)) { PylaLog.p(TAG, "State: popup"); return StateObservation("popup", neutralIdentity("popup")) }
        if (inBrawlPass(screenshot) || inStarRoad(screenshot)) {
            PylaLog.p(TAG, "State: shop (brawl_pass/star_road)")
            // These two screens carry their own configured control template, so their identity is
            // not ambiguous and needs no corroboration.
            return StateObservation("shop", MenuIdentityAdmission.confirmedByOwnControl("shop", "brawl_pass_or_star_road"))
        }
        PylaLog.p(TAG, "State: unknown (no positive menu evidence)")
        return StateObservation("unknown", neutralIdentity("unknown"))
    }
}