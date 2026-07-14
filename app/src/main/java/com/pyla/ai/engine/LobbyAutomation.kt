package com.pyla.ai.engine

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pyla.ai.config.PylaConfig
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.concurrent.TimeUnit

class LobbyAutomation(val windowController: WindowController) {

    private val verboseDebug: Boolean get() =
        PylaUtils.configBool(PylaConfig.load("cfg/debug_settings.toml").opt("verbose_debug"), false)
    private val grayPixelsThreshold: Long get() =
        PylaConfig.load("cfg/bot_config.toml").getDouble("idle_pixels_minimum", 500.0).toLong()
    private fun lobbyConfig() = PylaConfig.load("cfg/lobby_config.toml")

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val allBrawlersNames: Map<String, List<String>> by lazy {
        val f = PylaConfig.resolve("cfg/names.json")
        if (!f.exists()) emptyMap()
        else try {
            val root = JSONObject(f.readText())
            val out = HashMap<String, List<String>>()
            for (k in root.keys()) {
                val arr = root.getJSONArray(k)
                out[k] = (0 until arr.length()).map { arr.getString(it) }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "names.json load failed: ${t.message}")
            emptyMap()
        }
    }

    private fun centerX(base: Int, frameWidth: Int, hr: Float): Float =
        frameWidth / 2f + (base - 960) * hr

    fun checkForIdle(frame: Mat) {
        val hr = windowController.heightRatio
        if (hr <= 0f) return
        val w = frame.cols(); val h = frame.rows()
        val xStart = centerX(460, w, hr).toInt().coerceIn(0, w)
        val xEnd = centerX(1460, w, hr).toInt().coerceIn(0, w)
        val yStart = (400 * hr).toInt().coerceIn(0, h)
        val yEnd = (675 * hr).toInt().coerceIn(0, h)
        if (xEnd <= xStart || yEnd <= yStart) return
        val cropped = Mat(frame, Rect(xStart, yStart, xEnd - xStart, yEnd - yStart))
        val gray = PylaUtils.countHsvPixels(cropped, doubleArrayOf(0.0, 0.0, 20.0), doubleArrayOf(10.0, 15.0, 77.0))
        cropped.release()
        val threshold = grayPixelsThreshold * windowController.widthRatio * windowController.heightRatio
        if (verboseDebug) Log.i(TAG, "gray=$gray (threshold=${threshold.toInt()})")
        if (gray > threshold) {
            Log.i(TAG, "Idle detected, clicking to unidle")
            BotStatus.action("Idle popup detected, clicking to reconnect")
            windowController.click(centerX(540, w, hr), 630 * hr)
        }
    }

    private fun cleanName(raw: String): String {
        var v = raw.lowercase().trim()
        for (sym in listOf(" ", "-", ".", "&")) v = v.replace(sym, "")
        return v
    }

    private fun extractTextAndPositions(frame: FrameSnapshot): Map<String, Pair<Int, Int>> {
        val bitmap = PylaUtils.frameToBitmap(frame)
        val result = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
        } finally {
            bitmap.recycle()
        }
        val out = HashMap<String, Pair<Int, Int>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                out[line.text] = box.centerX() to box.centerY()
            }
        }
        return out
    }

    private fun stateOf(frame: FrameSnapshot): String {
        val mat = PylaUtils.frameToMat(frame)
        return try { StateFinder.getState(mat) } finally { mat.release() }
    }

    private fun findTextCenter(frame: FrameSnapshot, candidates: Set<String>): Pair<Int, Int>? {
        val results = try { extractTextAndPositions(frame) } catch (t: Throwable) { return null }
        for ((raw, pos) in results) {
            if (cleanName(raw) in candidates) return pos
        }
        return null
    }

    private fun openBrawlerMenu(hr: Float) {
        val frame = windowController.screenshot()
        val label = findTextCenter(frame, BRAWLERS_MENU_LABELS)
        if (label != null) {
            Log.i(TAG, "Brawlers menu label found at (${label.first},${label.second}), clicking it")
            windowController.click(label.first.toFloat(), label.second.toFloat())
        } else {
            val btn = lobbyConfig().getIntArray("lobby.brawler_btn")
            if (btn.size >= 2) {
                Log.i(TAG, "Brawlers menu using config coords (${btn[0]}, ${btn[1]})")
                windowController.click(btn[0] * hr, btn[1] * hr)
            } else {
                Log.i(TAG, "Brawlers menu using default fallback coords (110, 490)")
                windowController.click(110f * hr, 490f * hr)
            }
        }
    }

    private fun clickSelectButton(hr: Float) {
        val frame = windowController.screenshot()
        val label = findTextCenter(frame, SELECT_BUTTON_LABELS)
        if (label != null) {
            Log.i(TAG, "Select button found at (${label.first},${label.second}), clicking it")
            windowController.click(label.first.toFloat(), label.second.toFloat())
        } else {
            val sel = lobbyConfig().getIntArray("lobby.select_btn")
            if (sel.size >= 2) {
                Log.i(TAG, "Select button using config coords (${sel[0]}, ${sel[1]})")
                windowController.click(sel[0] * hr, sel[1] * hr)
            } else {
                Log.i(TAG, "Select button using default fallback coords (150, 950)")
                windowController.click(150f * hr, 950f * hr)
            }
        }
    }

    private val knownMenuNames: Set<String> by lazy {
        val out = HashSet<String>()
        for ((name, aliases) in allBrawlersNames) {
            out.add(cleanName(name))
            for (a in aliases) out.add(cleanName(a))
        }
        for (name in BrawlersInfo.load().keys) out.add(cleanName(name))
        out
    }

    fun selectBrawler(brawler: String): String {
        windowController.screenshot()
        val hr = windowController.heightRatio
        val target = cleanName(brawler)

        openBrawlerMenu(hr)
        sleep(800)

        Log.i(TAG, "Automatic brawler selection started for $target")
        BotStatus.action("Selecting brawler: $target")
        var notInMenuCounter = 0
        var firstScroll = true

        for (i in 0 until 100) {
            val frame = windowController.screenshot()
            val state = stateOf(frame)

            val results = try {
                extractTextAndPositions(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "Text recognition failed: ${t.message}")
                return "error"
            }
            val clean = HashMap<String, Pair<Int, Int>>()
            for ((k, v) in results) {
                if (k.length >= 2) clean[cleanName(k)] = v
            }

            val visibleKnownNames = clean.keys.count { it in knownMenuNames }
            val inMenu = state == "brawler_selection" || visibleKnownNames >= 2
            if (!inMenu) {
                notInMenuCounter++
                Log.i(TAG, "Not in brawler menu yet (state=$state, names=$visibleKnownNames, try=$notInMenuCounter)")
                if (state == "lobby" && notInMenuCounter % 3 == 0) {
                    Log.i(TAG, "Brawler menu did not open, clicking the button again")
                    openBrawlerMenu(hr)
                    sleep(800)
                }
                if (notInMenuCounter > 9) {
                    Log.w(TAG, "Could not reach the brawler menu, aborting selection")
                    return "stuck"
                }
                sleep(500)
                continue
            }
            notInMenuCounter = 0

            var matched: String? = if (clean.containsKey(target)) target else null
            if (matched == null) {
                val aliases = allBrawlersNames[target] ?: emptyList()
                for (detected in clean.keys) {
                    if (detected in aliases) {
                        matched = detected
                        Log.i(TAG, "Matched '$detected' to '$target' via alias list")
                        break
                    }
                }
            }

            if (matched != null) {
                val (x, y) = clean[matched]!!
                val yOffset = 50 * windowController.scaleFactor
                windowController.click(x.toFloat(), y - yOffset)
                Log.i(TAG, "Found $target ($matched), clicking at ($x,${(y - yOffset).toInt()})")
                sleep(1000)
                clickSelectButton(hr)
                sleep(1500)
                windowController.screenshot()
                Log.i(TAG, "Selected brawler $target")
                BotStatus.action("Selected brawler $target")
                return "success"
            }

            val sx = windowController.width - (1920 - 1700) * hr
            if (firstScroll) {
                windowController.swipe(sx, 900 * hr, sx, 850 * hr, 500)
                firstScroll = false
            } else {
                windowController.swipe(sx, 900 * hr, sx, 650 * hr, 500)
            }
            sleep(3000)
        }

        Log.w(TAG, "Brawler '$target' not found after 100 scroll attempts")
        return "failed"
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    companion object {
        private const val TAG = "PylaLobby"
        private val BRAWLERS_MENU_LABELS = setOf("brawlers", "brawler", "lottatori")
        private val SELECT_BUTTON_LABELS = setOf("select", "seleziona", "scegli", "auswahlen", "seleccionar", "selectionner")
    }
}
