package com.pyla.ai.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.os.SystemClock
import kotlin.math.abs
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.diagnostics.DiagnosticLog
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.util.concurrent.TimeUnit

class BrawlerSelectionFailedException(val reason: String) : IllegalStateException("Brawler selection failed ($reason)")

class LobbyAutomation(
    val windowController: WindowController,
    private val selectionAllowed: () -> Boolean = { true },
    private val selectionDelay: (Long) -> Unit = { Thread.sleep(it) },
    private val selectionClock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val verboseDebug: Boolean get() =
        PylaUtils.configBool(PylaConfig.load("cfg/debug_settings.toml").opt("verbose_debug"), false)
    private val grayPixelsThreshold: Long get() =
        PylaConfig.load("cfg/bot_config.toml").getDouble("idle_pixels_minimum", 500.0).toLong()
    private fun lobbyConfig() = PylaConfig.load("cfg/lobby_config.toml")
    private fun buttonsConfig() = PylaConfig.load("cfg/buttons_config.toml")

    private val recognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer get() = recognizerDelegate.value
    private var sourceBitmap: Bitmap? = null
    private var scaledOcrBitmap: Bitmap? = null
    private var gridOcrBitmap: Bitmap? = null
    private var lobbyReturnBitmap: Bitmap? = null
    private val lobbyReturnOcrReuse = BrawlerLobbyReturnGate.OcrReuse()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ocrScaleDownFactor: Double get() =
        PylaConfig.load("cfg/general_config.toml")
            .getDouble("ocr_scale_down_factor", 0.8)
            .coerceIn(0.1, 1.0)

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
            DiagnosticLog.failure("config", "names", "json_unreadable", t)
            Log.w(TAG, "names.json load failed: ${t.message}")
            emptyMap()
        }
    }

    private fun centerX(base: Int, frameWidth: Int, hr: Float): Float =
        frameWidth / 2f + (base - 960) * hr

    /**
     * Idle reconnect tap.
     *
     * [guard] is the caller's fail-closed lifecycle authority for this single reconnect: the caller
     * binds it to the session epoch and to the current frame (foreground, freshness, current-screen
     * evidence) and it is carried to the non-replayable navigation queue. The tap is routed through
     * [WindowController.guardedNavigationTap] — never a bare [WindowController.click] — so a
     * revoked guard denies the tap at the queue dispatch point as well as here. The default denies,
     * so an unconfigured caller cannot inject input. HSV window, crop, reconnect coordinates and the
     * 40 ms hold are unchanged from the PC parity path.
     */
    fun checkForIdle(frame: Mat, guard: () -> Boolean = EngineSessionAuthority.DENY) {
        val w = frame.cols(); val h = frame.rows()
        if (w <= 0 || h <= 0) return
        // The dialog is centred on every aspect ratio and follows the game's UI scale.
        val hr = com.pyla.ai.capture.UiLayout.unit(w, h).toFloat()
        if (hr <= 0f) return
        fun mapY(base: Int) = com.pyla.ai.capture.UiLayout.y(base.toDouble(), w, h).toFloat()
        val xStart = centerX(460, w, hr).toInt().coerceIn(0, w)
        val xEnd = centerX(1460, w, hr).toInt().coerceIn(0, w)
        val yStart = mapY(400).toInt().coerceIn(0, h)
        val yEnd = mapY(675).toInt().coerceIn(0, h)
        if (xEnd <= xStart || yEnd <= yStart) return
        val cropped = Mat(frame, Rect(xStart, yStart, xEnd - xStart, yEnd - yStart))
        // Same HSV window as the PC version (lobby_automation.check_for_idle): the dark,
        // low-saturation dim overlay shown behind the in-match inactivity dialog.
        val threshold = grayPixelsThreshold * (w / 1920f) * (h / 1080f) *
            if (com.pyla.ai.capture.UiLayout.isNarrow(w, h)) hr / (h / 1080f) else 1f
        val gray = try {
            val legacy = PylaUtils.countHsvPixels(cropped, doubleArrayOf(0.0, 0.0, 10.0), doubleArrayOf(30.0, 60.0, 67.0))
            if (legacy > threshold) legacy else flatDarkDialogPixels(cropped, threshold.toDouble())
        } finally { cropped.release() }
        if (verboseDebug) Log.i(TAG, "gray=$gray (threshold=${threshold.toInt()})")
        if (gray > threshold) {
            val btn = buttonsConfig().getIntArray("idle_reconnect")
            val bx = if (btn.size >= 2) btn[0] else 540
            val by = if (btn.size >= 2) btn[1] else 630
            // Re-evaluate the caller's authority immediately before the side effect. The same guard
            // is retained through the queued dispatch, so a revocation between here and dispatch
            // also denies the tap.
            if (!guard()) return
            Log.i(TAG, "Idle detected (gray=$gray), reconnecting at ($bx,$by)")
            BotStatus.action("Idle popup detected, reconnecting")
            windowController.guardedNavigationTap(centerX(bx, w, hr), mapY(by), guard)
        }
    }

    /**
     * The inactivity dialog is a system dialog, so its surface colour follows the device theme.
     * The PC hue window (0-30) only matches a neutral or warm grey: on an Android 13 device with a
     * cool-tinted dark theme the surface is RGB (26,28,30), hue 105, and the dialog was never
     * seen. Hue carries no information at this darkness and saturation, so any hue is accepted
     * here - but only for a flat surface: measured 87k matching pixels with a brightness spread
     * of 3.3 on the dialog, against at most 2.4k pixels (spread 15-17) on 140 other frames.
     * Returns 0 unless the surface is flat, so the caller's threshold comparison stays as is.
     */
    private fun flatDarkDialogPixels(cropped: Mat, threshold: Double): Int {
        val hsv = Mat(); val mask = Mat()
        val mean = org.opencv.core.MatOfDouble(); val deviation = org.opencv.core.MatOfDouble()
        return try {
            Imgproc.cvtColor(cropped, hsv, Imgproc.COLOR_RGB2HSV)
            org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(0.0, 0.0, 10.0),
                org.opencv.core.Scalar(180.0, 60.0, 67.0), mask)
            val count = org.opencv.core.Core.countNonZero(mask)
            if (count <= threshold) return 0
            org.opencv.core.Core.meanStdDev(hsv, mean, deviation, mask)
            val spread = deviation.toArray().getOrNull(2) ?: return 0
            if (spread.isFinite() && spread <= FLAT_DIALOG_BRIGHTNESS_SPREAD) count else 0
        } catch (_: Exception) { 0 } finally { hsv.release(); mask.release(); mean.release(); deviation.release() }
    }

    private fun cleanName(raw: String): String = BrawlerSelectionGate.normalize(raw)

    private fun extractTextAndPositions(frame: FrameSnapshot, timeoutMs: Long = 15_000L): BrawlerOcrScan =
        extractTextAndPositionsAtScale(frame, timeoutMs, ocrScaleDownFactor)

    // Small, outlined card names lose recognizability when downscaled, so the GRID path recognises
    // only the heading tile and the three card-title strips of the user-approved mosaic at their
    // planned pixel heights, instead of the whole frame at one scale. Entry/detail/lobby keep their
    // configured scale and every identity/freshness/geometry gate is unchanged.
    private fun extractGridTextAndPositions(frame: FrameSnapshot, timeoutMs: Long = 15_000L): BrawlerOcrScan {
        // Never append controls to the name mosaic: native ML Kit can change Nori's reading
        // when a fifth tile is appended. Fixed chrome is observed separately only when needed.
        val plan = BrawlerGridOcrRegions.plan(frame.width, frame.height)
            ?: return extractTextAndPositionsAtScale(frame, timeoutMs, 1.0)

        val oldSource = sourceBitmap
        val source = PylaUtils.frameToBitmap(frame, oldSource)
        if (source !== oldSource) oldSource?.recycle()
        sourceBitmap = source

        val mosaic = gridMosaic(plan, source)
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(mosaic, 0)),
            timeoutMs.coerceAtLeast(1L),
            TimeUnit.MILLISECONDS,
        )
        val headerTile = plan.tiles[0]
        val out = ArrayList<BrawlerOcrHit>()
        val headerHits = ArrayList<BrawlerOcrHit>()
        var unresolvedHeaderDuplicate = false
        // Choose word OR whole-line evidence per recognised line, never both for one line.
        // Distinct heading lines stay distinct so a mixed-representation duplicate is rejected.
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val mosaicBox = GridOcrRect(box.left, box.top, box.right, box.bottom)
                // A box bridging two tiles, sitting in a gutter or leaving the mosaic maps to
                // null and is dropped; it is never clipped into a neighbouring tile.
                val mapped = BrawlerGridOcrRegions.map(plan, mosaicBox) ?: continue
                if (inside(headerTile.destination, mosaicBox)) {
                    var headerWordEmitted = false
                    val elements = line.elements
                    val rawElements = ArrayList<String>(elements.size)
                    for (element in elements) rawElements.add(element.text)
                    // Only a line that is nothing but heading words plus bounded ASCII counter
                    // decoration can contribute a heading; any other word rejects the whole line.
                    if (BrawlerGridHeader.permitsLabelElements(rawElements)) {
                        // Count semantic labels before mapping: dropping an unlocated duplicate
                        // must never make its mapped neighbour look like a unique heading.
                        val labels = elements.filter { BrawlerGridHeader.isLabel(it.text) }
                        if (labels.size > 1 && labels.any { element ->
                                val elementBox = element.boundingBox
                                if (elementBox == null) true else {
                                    val candidate = GridOcrRect(
                                        elementBox.left, elementBox.top, elementBox.right, elementBox.bottom,
                                    )
                                    !inside(headerTile.destination, candidate) ||
                                        BrawlerGridOcrRegions.map(plan, candidate) == null
                                }
                            }) {
                            unresolvedHeaderDuplicate = true
                            continue
                        }
                        for (element in elements) {
                            if (!BrawlerGridHeader.isLabel(element.text)) continue
                            val elementBox = element.boundingBox ?: continue
                            val elementMosaicBox =
                                GridOcrRect(elementBox.left, elementBox.top, elementBox.right, elementBox.bottom)
                            if (!inside(headerTile.destination, elementMosaicBox)) continue
                            val elementMapped =
                                BrawlerGridOcrRegions.map(plan, elementMosaicBox) ?: continue
                            // Duplicates are kept: the caller's uniqueMatch rejects the ambiguity.
                            headerHits.add(
                                BrawlerOcrHit(
                                    element.text,
                                    elementMapped.left, elementMapped.top,
                                    elementMapped.right, elementMapped.bottom,
                                ),
                            )
                            headerWordEmitted = true
                        }
                    }
                    if (!headerWordEmitted && BrawlerGridHeader.isHeader(line.text)) {
                        headerHits.add(
                            BrawlerOcrHit(line.text, mapped.left, mapped.top, mapped.right, mapped.bottom),
                        )
                    }
                    continue
                }
                // Body tile: the exact original whole-line text and nothing else - no element
                // splitting, no alias normalisation, no reconstructed name.
                out.add(BrawlerOcrHit(line.text, mapped.left, mapped.top, mapped.right, mapped.bottom))
            }
        }
        // A different valid heading cannot erase unresolved duplicate evidence in this scan.
        // Never fabricate a bounding box or alter card text to encode the uncertainty.
        if (!unresolvedHeaderDuplicate) out.addAll(headerHits)
        return BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs, out,
            gridHeaderAmbiguous = unresolvedHeaderDuplicate)
    }

    /** True when [box] lies fully inside [tile]; a gutter or tile edge is therefore outside. */
    private fun inside(tile: GridOcrRect, box: GridOcrRect): Boolean =
        box.left >= tile.left && box.top >= tile.top &&
            box.right <= tile.right && box.bottom <= tile.bottom

    /**
     * Builds (and reuses) the mosaic bitmap for [plan] from the full original frame pixels.
     *
     * The bitmap is owned separately from [sourceBitmap] and [scaledOcrBitmap] so no other OCR
     * path is repurposed; it is only re-created when the plan's mosaic size changes and is recycled
     * in [close]. The canvas is filled black first, so the inter-tile gutters and the area right
     * of a narrow tile can never carry pixels of a previous frame.
     */
    private fun gridMosaic(plan: GridOcrPlan, source: Bitmap): Bitmap {
        val target = buildMosaicBitmap(plan.width, plan.height, plan.tiles, source, gridOcrBitmap)
        gridOcrBitmap = target
        return target
    }

    private fun buildMosaicBitmap(width: Int, height: Int, tiles: List<GridOcrTile>, source: Bitmap, previousTarget: Bitmap?): Bitmap {
        var target = previousTarget
        if (target == null || target.isRecycled || !target.isMutable ||
            target.width != width || target.height != height
        ) {
            target?.recycle()
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(target)
        canvas.drawColor(android.graphics.Color.BLACK)
        for (tile in tiles) {
            canvas.drawBitmap(
                source,
                android.graphics.Rect(tile.source.left, tile.source.top, tile.source.right, tile.source.bottom),
                android.graphics.Rect(
                    tile.destination.left, tile.destination.top,
                    tile.destination.right, tile.destination.bottom,
                ),
                scalePaint,
            )
        }
        return target
    }

    private fun extractTextAndPositionsAtScale(frame: FrameSnapshot, timeoutMs: Long, scale: Double): BrawlerOcrScan {
        val oldSource = sourceBitmap
        val source = PylaUtils.frameToBitmap(frame, oldSource)
        if (source !== oldSource) oldSource?.recycle()
        sourceBitmap = source

        val ocrBitmap = if (scale < 0.999) {
            val targetW = (source.width * scale).toInt().coerceAtLeast(1)
            val targetH = (source.height * scale).toInt().coerceAtLeast(1)
            var target = scaledOcrBitmap
            if (target == null || target.isRecycled || target.width != targetW || target.height != targetH) {
                target?.recycle()
                target = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                scaledOcrBitmap = target
            }
            Canvas(target).drawBitmap(
                source,
                null,
                android.graphics.Rect(0, 0, targetW, targetH),
                scalePaint,
            )
            target
        } else {
            source
        }
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)), timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        val inverseScale = 1.0 / scale
        val out = ArrayList<BrawlerOcrHit>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                out.add(BrawlerOcrHit(
                    line.text,
                    (box.left * inverseScale).toInt(), (box.top * inverseScale).toInt(),
                    (box.right * inverseScale).toInt(), (box.bottom * inverseScale).toInt(),
                ))
            }
        }
        return BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs, out)
    }

    /**
     * LOBBY-only OCR: recognise the two returned-lobby control regions as one small mosaic instead
     * of the whole frame at one scale.
     *
     * The two required labels are small control captions on a mostly-static lobby whose centre
     * animates continuously, and a whole-frame scan of it costs several seconds per call. Only the
     * two disjoint regions of [BrawlerLobbyReturnGate] are cropped into one narrow bitmap, the
     * configured OCR scale is retained, and every surviving box is mapped back to FULL-frame
     * coordinates. A box that cannot be mapped - bridging both tiles or sitting in the gutter - is
     * dropped and recorded, never clipped into a neighbour: when a control-labelled box was
     * dropped, [BrawlerLobbyReturnGate.uniqueControls] refuses the frame outright, so a lost
     * duplicate can never make its mapped neighbour look unique.
     *
     * When the capture is outside the plan's supported contract this returns a scan with no hits
     * instead of falling back to a whole-frame recognition: an unsupported frame must fail closed,
     * not be silently observed by a different, larger budget.
     */
    private fun extractLobbyReturnTextAndPositions(frame: FrameSnapshot, timeoutMs: Long): BrawlerOcrScan {
        val permille = (ocrScaleDownFactor * 1_000.0).toInt().coerceIn(100, 1_000)
        val plan = BrawlerLobbyReturnGate.plan(frame.width, frame.height, permille)
            ?: return BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs, emptyList())

        val oldSource = sourceBitmap
        val source = PylaUtils.frameToBitmap(frame, oldSource)
        if (source !== oldSource) oldSource?.recycle()
        sourceBitmap = source

        val mosaic = lobbyReturnMosaic(plan, source)
        val rendered = IntArray(mosaic.width * mosaic.height)
        mosaic.getPixels(rendered, 0, mosaic.width, 0, 0, mosaic.width, mosaic.height)
        lobbyReturnOcrReuse.reuse(frame, plan, rendered, selectionClock())?.let { return it }
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(mosaic, 0)),
            timeoutMs.coerceAtLeast(1L),
            TimeUnit.MILLISECONDS,
        )
        val out = ArrayList<BrawlerOcrHit>()
        var unmappedControl = false
        // One representation per physical line: do not also emit its word elements or merge
        // overlapping lines. Every distinct recognized control remains a uniqueness candidate.
        for (block in result.textBlocks) for (line in block.lines) {
            val isControl = BrawlerLobbyReturnGate.isBrawlersLabel(line.text) ||
                BrawlerLobbyReturnGate.isPlayLabel(line.text)
            val box = line.boundingBox
            val mapped = box?.let {
                BrawlerLobbyReturnGate.map(plan, GridOcrRect(it.left, it.top, it.right, it.bottom))
            }
            if (mapped == null) {
                if (isControl) unmappedControl = true
            } else {
                out.add(BrawlerOcrHit(line.text, mapped.left, mapped.top, mapped.right, mapped.bottom))
            }
        }
        val scan = BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs,
            if (unmappedControl) emptyList() else out)
        lobbyReturnOcrReuse.remember(scan, plan, rendered, selectionClock())
        return scan
    }

    /**
     * Builds (and reuses) the returned-lobby mosaic bitmap for [plan] from the full original frame
     * pixels. Owned separately from the other OCR bitmaps, and recycled in [close].
     */
    private fun lobbyReturnMosaic(plan: BrawlerLobbyReturnGate.Plan, source: Bitmap): Bitmap {
        val target = buildMosaicBitmap(plan.width, plan.height, plan.tiles, source, lobbyReturnBitmap)
        lobbyReturnBitmap = target
        return target
    }

    /**
     * DETAIL-only OCR: retain the complete left identity/button area plus a 10%-screen guard band.
     * The rightmost quarter contains no admissible title/button (both must end before 65%).
     * Preserve original resolution scale, full-frame coordinates and timestamps; never extend age.
     */
    private fun extractDetailTextAndPositions(frame: FrameSnapshot, timeoutMs: Long): BrawlerOcrScan {
        require(frame.width > 0 && frame.height > 0 && frame.argb.size.toLong() == frame.width.toLong() * frame.height)
        val cropWidth = (frame.width * 0.75).toInt().coerceAtLeast(1)
        val pixels = IntArray(cropWidth * frame.height)
        for (y in 0 until frame.height) frame.argb.copyInto(pixels, y * cropWidth, y * frame.width, y * frame.width + cropWidth)
        val cropped = FrameSnapshot(cropWidth, frame.height, pixels, frame.capturedAtMs)
        return extractTextAndPositions(cropped, timeoutMs).copy(width = frame.width)
    }

    private fun stateOf(frame: FrameSnapshot): String {
        val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()
        val mat = PylaUtils.frameToMat(frame)
        return try { StateFinder.getState(mat, chaosGeneration, frame.capturedAtMs) } finally { mat.release() }
    }

    private enum class ScanRegion { GRID, DETAIL, LOBBY }
    private data class ScanPair(
        val firstFrame: FrameSnapshot,
        val secondFrame: FrameSnapshot,
        val first: BrawlerOcrScan,
        val second: BrawlerOcrScan,
    )
    private var selectionWidth = 0
    private var selectionHeight = 0

    // WindowController reuses its copy buffer. Retained scan/stability frames must own their pixels.
    private fun freshFrame(): FrameSnapshot? {
        val frame = windowController.getLatestFrame().first ?: return null
        val now = selectionClock()
        if (frame.width != selectionWidth || frame.height != selectionHeight ||
            frame.capturedAtMs <= 0L || frame.capturedAtMs > now ||
            now - frame.capturedAtMs > FRAME_MAX_AGE_MS ||
            frame.argb.size.toLong() != frame.width.toLong() * frame.height) return null
        return FrameSnapshot(frame.width, frame.height, frame.argb.copyOf(), frame.capturedAtMs)
    }

    private fun allowed(deadline: Long): Boolean =
        selectionAllowed() && !Thread.currentThread().isInterrupted && selectionClock() < deadline

    private fun waitStep(ms: Long, deadline: Long): Boolean {
        if (!allowed(deadline)) return false
        try { selectionDelay(ms) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return allowed(deadline)
    }

    // Ignore the animated character on the detail screen; watch its static title/button side.
    private fun pixelsStill(a: FrameSnapshot, b: FrameSnapshot, region: ScanRegion): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        var changed = 0
        var totalDifference = 0L
        for (row in 0 until 20) for (col in 0 until 20) {
            val x = ((if (region == ScanRegion.DETAIL) 0.04 + col * 0.023 else 0.04 + col * 0.046) * a.width).toInt()
            val y = ((if (region == ScanRegion.GRID) 0.16 + row * 0.039 else 0.08 + row * 0.046) * a.height).toInt()
            val aa = a.argb[y * a.width + x]; val bb = b.argb[y * b.width + x]
            val delta = abs(((aa ushr 16) and 255) - ((bb ushr 16) and 255)) +
                abs(((aa ushr 8) and 255) - ((bb ushr 8) and 255)) + abs((aa and 255) - (bb and 255))
            totalDifference += delta
            if (delta > 30) changed++
        }
        return changed <= 20 && totalDifference <= 400L * 9L
    }

    private fun gridScan(frame: FrameSnapshot, deadline: Long): BrawlerOcrScan? = try {
        extractGridTextAndPositions(frame, minOf(2_000L, (deadline - selectionClock()).coerceAtLeast(1L)))
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (t: Exception) {
        DiagnosticLog.event("selection.search", "reason=grid_ocr_retry")
        DiagnosticLog.failure("selection", "grid_ocr", "ocr_exception", t)
        null
    }

    private fun stableFrame(notBefore: Long, region: ScanRegion, deadline: Long,
        listSession: BrawlerListSearchSession? = null): FrameSnapshot? {
        var previous: FrameSnapshot? = null
        while (allowed(deadline)) {
            val frame = freshFrame()
            if (frame != null && frame.capturedAtMs >= notBefore) {
                val prior = previous
                if (prior != null && frame.capturedAtMs > prior.capturedAtMs &&
                    ((region == ScanRegion.GRID && listSession?.confirms(prior, frame, selectionClock()) == true) ||
                        pixelsStill(prior, frame, region))) return frame
                if (prior == null || frame.capturedAtMs > prior.capturedAtMs) previous = frame
            }
            if (!waitStep(SCAN_INTERVAL_MS, deadline)) break
        }
        return null
    }

    private fun settledPair(notBefore: Long, region: ScanRegion, deadline: Long,
        listSession: BrawlerListSearchSession? = null): ScanPair? {
        for (attempt in 0 until 3) {
            val firstFrame = stableFrame(notBefore, region, deadline, listSession) ?: return null
            val first = if (region == ScanRegion.GRID) {
                gridScan(firstFrame, deadline) ?: continue
            } else if (region == ScanRegion.DETAIL) {
                extractDetailTextAndPositions(firstFrame, minOf(15_000L, (deadline - selectionClock()).coerceAtLeast(1L)))
            } else extractTextAndPositions(firstFrame)
            if (!waitStep(SCAN_INTERVAL_MS, deadline)) return null
            val secondFrame = stableFrame(firstFrame.capturedAtMs + SCAN_INTERVAL_MS, region, deadline, listSession) ?: return null
            val second = if (region == ScanRegion.GRID) {
                gridScan(secondFrame, deadline) ?: continue
            } else if (region == ScanRegion.DETAIL) {
                extractDetailTextAndPositions(secondFrame, minOf(15_000L, (deadline - selectionClock()).coerceAtLeast(1L)))
            } else extractTextAndPositions(secondFrame)
            val now = selectionClock()
            if (allowed(deadline) && second.capturedAtMs > first.capturedAtMs &&
                now - first.capturedAtMs <= FRAME_MAX_AGE_MS && now - second.capturedAtMs <= FRAME_MAX_AGE_MS &&
                ((region == ScanRegion.GRID && listSession?.confirms(firstFrame, secondFrame, now) == true) ||
                    pixelsStill(firstFrame, secondFrame, region))) return ScanPair(firstFrame, secondFrame, first, second)
        }
        return null
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

    private fun targetNames(target: String): Set<String> {
        val out = hashSetOf(target)
        for ((canonical, aliases) in allBrawlersNames) {
            if (cleanName(canonical) == target) out.addAll(aliases.map(::cleanName))
        }
        // A configured alias shared by different brawlers is not an identity proof.
        val otherNames = allBrawlersNames.filterKeys { cleanName(it) != target }
            .flatMap { (name, aliases) -> aliases + name }.map(::cleanName).toSet()
        return out.filter { it.isNotEmpty() && it !in otherNames }.toSet()
    }

    private fun inGrid(pair: ScanPair): Boolean {
        val originalIdentity = listOf(pair.first to pair.firstFrame, pair.second to pair.secondFrame).all { (scan, frame) ->
        val known = scan.hits.filter {
            it.top >= scan.height * GRID_TOP && it.bottom <= scan.height * GRID_BOTTOM && cleanName(it.text) in knownMenuNames
        }.map { cleanName(it.text) }.distinct().size
        // Only the exact fixed heading grammar may shed its count metadata. Card/detail names
        // continue to use the original scan and strict canonical/alias matching, never substrings.
        val headerScan = scan.copy(hits = scan.hits.map { hit ->
            if (BrawlerGridHeader.isHeader(hit.text)) hit.copy(text = "brawler") else hit
        })
        val header = BrawlerSelectionGate.uniqueMatch(headerScan, BRAWLERS_MENU_LABELS, 0.0, GRID_TOP) != null
            known >= 2 && (stateOf(frame) == "brawler_selection" || header)
        }
        // Initial/cold identity stays strict. A previously admitted list continues only through
        // the ORIGINAL run-local visual binding in admitGrid; fresh contrast in an arbitrary new
        // control region must never re-anchor an unknown/foreign screen from three names alone.
        return originalIdentity
    }

    private fun confirmed(pair: ScanPair, names: Set<String>, top: Double, bottom: Double): BrawlerOcrHit? =
        BrawlerSelectionGate.confirmPair(pair.first, pair.second, names, top, bottom, selectionClock())

    /**
     * DETAIL-only SELECT button: the recognized-label path first, the glyph-shape fallback only when
     * neither scan has ANY normalized SELECT label in the admissible band.
     *
     * The fallback is strictly supplemental. It never overrides a recognized-but-rejected label (a
     * duplicate or a moving recognized box still fails closed through [confirmed]), never widens the
     * band, and grants no authority: the caller still requires the detail title and still runs its
     * immediate live gate before clicking.
     */
    private fun confirmedSelect(pair: ScanPair): BrawlerOcrHit? {
        val recognized = confirmed(pair, SELECT_BUTTON_LABELS, BrawlerDetailSelect.BAND_TOP, BrawlerDetailSelect.BAND_BOTTOM)
        if (recognized != null) return recognized
        // Only when the recognizer produced no SELECT label at all in this band, in either frame.
        if (hasSelectLabel(pair.first) || hasSelectLabel(pair.second)) return null
        return BrawlerDetailSelect.confirmPair(pair.firstFrame, pair.secondFrame, selectionClock())
    }

    /** True when a scan carries any SELECT label inside the band, recognized or otherwise. */
    private fun hasSelectLabel(scan: BrawlerOcrScan): Boolean {
        val bandTop = BrawlerDetailSelect.BAND_TOP * scan.height
        val bandBottom = BrawlerDetailSelect.BAND_BOTTOM * scan.height
        return scan.hits.any {
            it.width > 0 && it.height > 0 &&
                cleanName(it.text) in SELECT_BUTTON_LABELS &&
                it.top >= bandTop && it.bottom <= bandBottom
        }
    }

    /**
     * DETAIL-only control pair: two DISTINCT fresh captures whose uniquely recognized title and
     * SELECT control agree, and whose OWN bounded control windows ([BrawlerDetailStability]) are
     * pixel-stable across the pair.
     *
     * Continuous animation anywhere else on the detail screen is tolerated: only the observed
     * controls' vicinities are compared, never the whole left half. A title or SELECT that
     * disappeared, moved, changed, became ambiguous or got covered cannot form the pair at all.
     *
     * Transient OCR unavailability for one capture is caught HERE, in the same finite phase, and
     * simply re-observed: it is never propagated as a generic engine error and never extends
     * [deadline]. The pair is never formed from a single frame, a stale/retro capture or a
     * regressed timestamp.
     */
    private fun detailPair(names: Set<String>, deadline: Long): ScanPair? {
        var attempt = 0
        while (allowed(deadline) && attempt < MAX_DETAIL_PAIR_ATTEMPTS) {
            attempt++
            observeDetailPair(names, deadline)?.let { return it }
        }
        return null
    }

    /** One bounded attempt to observe a control pair; every rejection advances the same phase. */
    private fun observeDetailPair(names: Set<String>, deadline: Long): ScanPair? {
        fun pause(): Boolean = waitStep(SCAN_INTERVAL_MS, deadline)
        // Two DISTINCT fresh captures first, then BOTH scans: the recognizer is never asked for a
        // pair from a single frame, and no capture is taken between a scan and the controls it
        // yields (so the caller - not this pair - owns the capture immediately after the scans).
        val firstFrame = freshFrame() ?: run { pause(); return null }
        if (!pause()) return null
        val secondFrame = freshFrame() ?: run { pause(); return null }
        if (secondFrame.capturedAtMs <= firstFrame.capturedAtMs) { pause(); return null }
        val first = detailScan(firstFrame, deadline) ?: run { pause(); return null }
        val second = detailScan(secondFrame, deadline) ?: run { pause(); return null }
        // Distinct, fresh, same-geometry pair only. Old/regressed/future stamps fail here.
        if (!BrawlerLobbyReturnGate.distinctFreshPair(first, second, selectionClock())) return null
        // A unique, located SELECT in each frame, in the same place, is the pair's binding control.
        val firstSelect = detailSelect(firstFrame, first) ?: return null
        val secondSelect = detailSelect(secondFrame, second) ?: return null
        if (!boxesAgree(second.height, firstSelect, secondSelect)) return null
        val windows = ArrayList<LobbyMenuEntryGate.Bounds>(BrawlerDetailStability.WINDOW_COUNT)
        LobbyMenuEntryGate.aroundLabel(second.width, second.height, secondSelect)?.let { windows.add(it) }
        // A unique known title adds its own stability window. An absent, ambiguous or conflicting
        // title contributes no window here and stays the CALLER's identity rejection, never this
        // pair's silent downgrade.
        val firstTitle = detailTitle(first, names)
        val secondTitle = detailTitle(second, names)
        if (firstTitle != null && secondTitle != null && boxesAgree(second.height, firstTitle, secondTitle)) {
            LobbyMenuEntryGate.aroundLabel(second.width, second.height, secondTitle)?.let { windows.add(it) }
        }
        // Only the located control windows must hold across the pair; animation elsewhere is ignored.
        if (!BrawlerDetailStability.controlsStill(firstFrame, secondFrame, windows)) return null
        return ScanPair(firstFrame, secondFrame, first, second)
    }

    /**
     * One DETAIL OCR bounded by the remaining phase budget. A transient recognizer timeout is NOT
     * a phase failure: it is returned as `null` so the caller re-observes a fresh frame inside the
     * SAME finite phase. An interrupt still propagates and is never swallowed.
     */
    private fun detailScan(frame: FrameSnapshot, deadline: Long): BrawlerOcrScan? {
        // A stalled OCR may consume only one attempt, not the complete recovery phase.
        val budget = minOf(2_000L, (deadline - selectionClock()).coerceAtLeast(1L))
        return try {
            extractDetailTextAndPositions(frame, budget)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (t: java.util.concurrent.TimeoutException) {
            DiagnosticLog.failure("selection", "detail_ocr", "ocr_timeout", t)
            null
        }
    }

    /** The unique known-name title box in the left identity band of [scan], or `null`. */
    private fun detailTitle(scan: BrawlerOcrScan, names: Set<String>): BrawlerOcrHit? {
        val left = scan.copy(hits = scan.hits.filter { it.right <= scan.width * 0.65 })
        return BrawlerSelectionGate.uniqueMatch(left, knownMenuNames + names, DETAIL_TOP, DETAIL_BOTTOM)
    }

    /** The unique SELECT control of [scan]/[frame]: recognized label first, glyph fallback second. */
    private fun detailSelect(frame: FrameSnapshot, scan: BrawlerOcrScan): BrawlerOcrHit? {
        val recognized = BrawlerSelectionGate.uniqueMatch(
            scan, SELECT_BUTTON_LABELS, BrawlerDetailSelect.BAND_TOP, BrawlerDetailSelect.BAND_BOTTOM,
        )
        if (recognized != null) return recognized
        // A recognized-but-ambiguous label must never fall through to the glyph path.
        if (hasSelectLabel(scan)) return null
        return BrawlerDetailSelect.locate(frame.width, frame.height, frame.argb)?.hit
    }

    /**
     * True when two observed control boxes describe the same word in the same place, within the
     * unchanged [BrawlerSelectionGate.tolerancePx] geometry slack.
     */
    private fun boxesAgree(height: Int, a: BrawlerOcrHit, b: BrawlerOcrHit): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        if (cleanName(a.text) != cleanName(b.text)) return false
        val tolerance = BrawlerSelectionGate.tolerancePx(height)
        return abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
    }

    /**
     * Bounded DETAIL confirmation immediately below the committed card click.
     *
     * The target name and SELECT are proven on a fresh control pair ([detailPair]) whose OWN title
     * and SELECT windows are stable - continuous character/background animation elsewhere on the
     * detail screen no longer denies an otherwise valid confirmation. The immediately preceding
     * live gate is cleared on the SAME control windows.
     *
     * A pre-dispatch live pixel/freshness denial is evidence loss, not a wrong target: the rejected
     * pair is discarded and the SAME target name and SELECT are RE-DERIVED from an entirely new
     * control pair, bounded by the caller's finite [detailDeadline] and a finite attempt cap.
     *
     * Nothing else is relaxed: the committed card is never re-clicked, the rejected SELECT
     * coordinates are never reused, the title/target geometry rules are untouched, and a withdrawn
     * authority, interruption, spent deadline, wrong/missing/ambiguous/moving title or SELECT,
     * stale/regressed capture or an exhausted attempt bound all still fail closed.
     *
     * @return [DetailConfirm.Selected] with the proven SELECT, [DetailConfirm.Unsettled] when no
     *   control pair formed inside the phase, [DetailConfirm.Rejected] for an identity/geometry
     *   rejection the caller names as the detail phase boundary, or [DetailConfirm.Denied] for a
     *   terminal denial the caller reports unchanged.
     */
    private fun confirmDetailSelect(names: Set<String>, deadline: Long, detailDeadline: Long): DetailConfirm {
        var attempt = 0
        var identityAttempt = 0
        while (true) {
            // Authority/cancellation and a spent phase stay terminal, never an unbounded wait.
            if (cancelled()) return DetailConfirm.Denied
            if (selectionClock() >= detailDeadline) return DetailConfirm.Unsettled
            val detail = detailPair(names, detailDeadline) ?: return DetailConfirm.Unsettled
            val title = confirmedIdentity(detail, names, leftOnly = true)
            val select = confirmedSelect(detail)
            if (title == null || title.right > detail.second.width * 0.65 || select == null ||
                select.right > detail.second.width * 0.65
            ) {
                // A pair can form while the detail page is still sliding in: SELECT is already
                // readable, the title not yet. A user report showed exactly this - rejected 0.9 s
                // after the card click, which stopped the bot. Nothing has been clicked for this
                // pair, so re-observing a NEW pair inside the same finite phase stays fail-closed;
                // a title that never becomes the unique target still ends as Rejected.
                identityAttempt++
                if (identityAttempt >= MAX_DETAIL_IDENTITY_ATTEMPTS) return DetailConfirm.Rejected
                DiagnosticLog.event("selection.detail", "reason=identity_retry")
                if (!waitStep(SCAN_INTERVAL_MS, detailDeadline)) return DetailConfirm.Rejected
                continue
            }
            val windows = BrawlerDetailStability.windows(
                detail.second.width, detail.second.height, title, select,
            ) ?: return DetailConfirm.Rejected
            // The confirmed controls must be stable across the pair's own captures.
            if (!BrawlerDetailStability.controlsStill(detail.firstFrame, detail.secondFrame, windows)) {
                attempt++
                if (attempt >= MAX_DETAIL_CONFIRM_ATTEMPTS) return DetailConfirm.Unsettled
                continue
            }
            val denial = liveGate(detail, ScanRegion.DETAIL, deadline, windows)
                ?: return DetailConfirm.Selected(select)
            // A live pixel/freshness denial is evidence loss: re-derive from a NEW pair.
            // Authority, interruption and a spent deadline stay terminal.
            if (denial != LiveReason.PIXELS_CHANGED && denial != LiveReason.FRAME_NOT_FRESH) {
                return DetailConfirm.Denied
            }
            attempt++
            if (attempt >= MAX_DETAIL_CONFIRM_ATTEMPTS) return DetailConfirm.Denied
        }
    }

    /**
     * Outcome of [confirmDetailSelect]. A bare terminal live denial is kept distinct from an
     * identity/geometry rejection so the caller's existing phase-terminal vocabulary is preserved.
     */
    private sealed interface DetailConfirm {
        class Selected(val hit: BrawlerOcrHit) : DetailConfirm
        object Unsettled : DetailConfirm
        object Rejected : DetailConfirm
        object Denied : DetailConfirm
    }

    private fun confirmedIdentity(pair: ScanPair, names: Set<String>, leftOnly: Boolean = false): BrawlerOcrHit? {
        fun titles(scan: BrawlerOcrScan): BrawlerOcrScan = if (!leftOnly) scan else
            scan.copy(hits = scan.hits.filter { it.right <= scan.width * 0.65 })
        val identities = pair.copy(first = titles(pair.first), second = titles(pair.second))
        // Presence alone is not identity: a different known title beside target description is a conflict.
        return confirmed(identities, knownMenuNames + names, DETAIL_TOP, DETAIL_BOTTOM)
            ?.takeIf { cleanName(it.text) in names }
    }

    /**
     * Live-action authority for an already proven pair, with a fixed rejection vocabulary.
     *
     * The original viewport, age and pixel thresholds are preserved. Authority, age and deadline
     * are also rechecked after inspection so its own work cannot extend an earlier observation.
     * Each token identifies a missing observation without OCR text, names, pixel values,
     * coordinates, timings, device identifiers or exception details.
     */
    private fun liveDenial(
        pair: ScanPair,
        region: ScanRegion,
        deadline: Long,
        windows: List<LobbyMenuEntryGate.Bounds>? = null,
    ): LiveReason? = inspectLiveDenial(pair, region, deadline, windows, null)

    private fun inspectLiveDenial(
        pair: ScanPair,
        region: ScanRegion,
        deadline: Long,
        windows: List<LobbyMenuEntryGate.Bounds>?,
        listSession: BrawlerListSearchSession?,
    ): LiveReason? {
        if (!selectionAllowed()) return LiveReason.AUTHORITY_WITHDRAWN
        if (Thread.currentThread().isInterrupted) return LiveReason.INTERRUPTED
        if (selectionClock() >= deadline) return LiveReason.DEADLINE
        // One capture, classified: unavailable, incompatible with the selected viewport, or stale.
        val frame = windowController.getLatestFrame().first ?: return LiveReason.FRAME_UNAVAILABLE
        val now = selectionClock()
        if (frame.width != selectionWidth || frame.height != selectionHeight ||
            frame.argb.size.toLong() != frame.width.toLong() * frame.height
        ) return LiveReason.FRAME_INCOMPATIBLE
        if (frame.capturedAtMs <= 0L || frame.capturedAtMs > now ||
            now - frame.capturedAtMs > FRAME_MAX_AGE_MS
        ) return LiveReason.FRAME_NOT_FRESH
        val live = FrameSnapshot(frame.width, frame.height, frame.argb.copyOf(), frame.capturedAtMs)
        if (live.capturedAtMs < pair.second.capturedAtMs) return LiveReason.CAPTURE_REGRESSED
        if (selectionClock() - pair.first.capturedAtMs > FRAME_MAX_AGE_MS) return LiveReason.FIRST_PAIR_EXPIRED
        // DETAIL supplies the confirmed title/SELECT control windows, so continuous animation
        // elsewhere on the detail screen is ignored while a moved or covered control still fails.
        // GRID supplies ONLY the confirmed target card label window: the list around it animates.
        val stable = when {
            region == ScanRegion.GRID && listSession != null ->
                listSession.matches(pair.secondFrame, now) && listSession.matches(live, now)
            windows == null -> pixelsStill(pair.secondFrame, live, region)
            region == ScanRegion.GRID ->
                BrawlerGridTargetStability.targetStill(pair.secondFrame, live, windows)
            else -> BrawlerDetailStability.controlsStill(pair.secondFrame, live, windows)
        }
        if (!stable) return LiveReason.PIXELS_CHANGED
        // Copying and pixel inspection consume time; do not grant from a pre-inspection clock.
        if (!selectionAllowed()) return LiveReason.AUTHORITY_WITHDRAWN
        if (Thread.currentThread().isInterrupted) return LiveReason.INTERRUPTED
        val finalNow = selectionClock()
        if (finalNow >= deadline) return LiveReason.DEADLINE
        if (finalNow - pair.first.capturedAtMs > FRAME_MAX_AGE_MS) return LiveReason.FIRST_PAIR_EXPIRED
        if (finalNow - live.capturedAtMs > FRAME_MAX_AGE_MS) return LiveReason.FRAME_NOT_FRESH
        return null
    }

    /**
     * The live gate used immediately before every input.
     *
     * It can only be left OPEN by an observation taken AFTER the last diagnostic this gate wrote, so
     * logger latency can never extend evidence and no input is ever accepted on an earlier check. A
     * rejection is reported with its fixed token and returned to the caller, which decides between a
     * terminal denial and a bounded GRID recovery; the gate itself never resends anything.
     */
    private fun liveGate(
        pair: ScanPair,
        region: ScanRegion,
        deadline: Long,
        windows: List<LobbyMenuEntryGate.Bounds>? = null,
    ): LiveReason? {
        val denial = liveDenial(pair, region, deadline, windows)
        if (denial != null) {
            noteLive(region, denial)
            return denial
        }
        noteLive(region, LiveReason.AUTHORISED)
        val after = liveDenial(pair, region, deadline, windows) ?: return null
        noteLive(region, after)
        noteLive(region, LiveReason.DENIED_AFTER_TELEMETRY)
        return after // Preserve terminal authority/deadline causes after logger latency.
    }

    /** Navigation only: card clicks keep the original exact target-bound liveGate. */
    private fun listScrollGate(pair: ScanPair, deadline: Long,
        listSession: BrawlerListSearchSession?): LiveReason? {
        if (listSession == null) return liveGate(pair, ScanRegion.GRID, deadline)
        val denial = inspectLiveDenial(pair, ScanRegion.GRID, deadline, null, listSession)
        if (denial != null) {
            noteLive(ScanRegion.GRID, denial)
            return denial
        }
        noteLive(ScanRegion.GRID, LiveReason.AUTHORISED)
        val after = inspectLiveDenial(pair, ScanRegion.GRID, deadline, null, listSession) ?: return null
        noteLive(ScanRegion.GRID, after)
        noteLive(ScanRegion.GRID, LiveReason.DENIED_AFTER_TELEMETRY)
        return after
    }

    /**
     * Authority and deadline denials end the run by choice or by timeout and are never recovery.
     * Only missing live EVIDENCE is retryable, and only inside the current page's existing budget.
     */
    private fun liveTerminal(denial: LiveReason): Boolean = when (denial) {
        LiveReason.AUTHORITY_WITHDRAWN, LiveReason.INTERRUPTED, LiveReason.DEADLINE -> true
        else -> false
    }

    /**
     * Fixed admission vocabulary. These tokens name WHICH observation was missing; they never carry
     * OCR text, frame content, coordinates, timings or device identifiers.
     */
    private enum class GridReason(val token: String) {
        ADMITTED("grid_admitted"),
        SESSION_CONFIRMED("grid_session_confirmed"),
        PAIR_UNSETTLED("grid_pair_unsettled"),
        IDENTITY_INCOMPLETE("grid_identity_incomplete"),
        FRESHNESS_REJECTED("grid_freshness_rejected"),
        ATTEMPT_LIMIT("grid_attempt_limit"),
        PHASE_TIMEOUT("grid_phase_timeout"),
        CANCELLED("grid_cancelled"),
    }

    private fun noteGrid(reason: GridReason) = DiagnosticLog.event("selection.grid", "reason=${reason.token}")

    /**
     * Fixed live-gate vocabulary. These tokens name WHICH live observation was missing or withdrawn
     * before an input. They never carry OCR text, names, pixel values, coordinates, timings, device
     * identifiers or exception detail, and each live gate site owns one bounded channel.
     */
    private enum class LiveReason(val token: String) {
        AUTHORISED("live_precheck_passed"),
        AUTHORITY_WITHDRAWN("live_authority_withdrawn"),
        INTERRUPTED("live_interrupted"),
        DEADLINE("live_deadline"),
        FRAME_UNAVAILABLE("live_frame_unavailable"),
        FRAME_INCOMPATIBLE("live_frame_incompatible"),
        FRAME_NOT_FRESH("live_frame_not_fresh"),
        CAPTURE_REGRESSED("live_capture_regressed"),
        FIRST_PAIR_EXPIRED("live_first_pair_expired"),
        PIXELS_CHANGED("live_pixels_changed"),
        DENIED_AFTER_TELEMETRY("live_denied_after_telemetry"),
    }

    private fun noteLive(region: ScanRegion, reason: LiveReason) =
        DiagnosticLog.event("selection.live.${region.name.lowercase(java.util.Locale.ROOT)}", "reason=${reason.token}")

    /**
     * Fixed terminal phase vocabulary for the DETAIL and LOBBY gates. These gates keep their prior
     * terminal behaviour and gain only the name of the boundary that ended the run. A committed
     * gesture is never retried and downstream policy is never widened. Tokens carry no OCR text,
     * name, pixel value, coordinate, timing, device identifier or exception detail.
     */
    private fun notePhaseTerminal(result: String, region: ScanRegion, reason: String): String {
        DiagnosticLog.event(
            "selection.phase.${region.name.lowercase(java.util.Locale.ROOT)}",
            "reason=$reason outcome=$result",
        )
        return result
    }

    /** Pre-claim guard terminal: exactly one line, fixed codes only, never folded. */
    private fun selectTerminal(result: String, reason: String): String {
        DiagnosticLog.terminal("selection", "select", result, reason)
        return result
    }

    /** A raised cancellation outranks a deadline: the run stopped by choice, not by timeout. */
    private fun cancelled(): Boolean = !selectionAllowed() || Thread.currentThread().isInterrupted

    /**
     * Fixed terminal vocabulary for the post-SELECT handoff.
     *
     * These tokens name WHICH positive lobby observation was missing. They never carry OCR text,
     * names, pixel values, coordinates, timings, device identifiers or exception detail.
     */
    private enum class LobbyReturnReason(val token: String) {
        CONTROLS_UNCONFIRMED("lobby_controls_unconfirmed"),
        LOBBY_STATE_UNCONFIRMED("lobby_state_unconfirmed"),
        EVIDENCE_EXPIRED("lobby_return_expired"),
        LIVE_CONTROL_UNCONFIRMED("lobby_live_control_unconfirmed"),
    }

    private fun noteLobbyReturn(reason: LobbyReturnReason) =
        DiagnosticLog.event("selection.return", "reason=${reason.token}")

    /**
     * The uniquely confirmed returned-lobby controls of [pair], ordered `BRAWLERS` then `PLAY`, or
     * `null`. No side effects and no authority: it only answers what the two retained scans show.
     *
     * Every check here is one this gate owes the caller - distinct fresh captures in the correct
     * order, the same observed control in the same place in both, unique exact labels inside their
     * own region, and bounded pixel-stable windows around BOTH observed labels. Screen state,
     * deadline, cancellation and the live frame stay with the caller, which is the only place that
     * can see the current authority.
     */
    private fun confirmedLobbyReturn(pair: ScanPair): List<BrawlerOcrHit>? {
        val first = pair.first
        val second = pair.second
        val permille = (ocrScaleDownFactor * 1_000.0).toInt().coerceIn(100, 1_000)
        val plan = BrawlerLobbyReturnGate.plan(second.width, second.height, permille) ?: return null
        if (first.width != second.width || first.height != second.height) return null
        for ((scan, frame) in listOf(first to pair.firstFrame, second to pair.secondFrame)) {
            if (scan.width != frame.width || scan.height != frame.height ||
                scan.capturedAtMs != frame.capturedAtMs) return null
        }
        if (!BrawlerLobbyReturnGate.distinctFreshPair(first, second, selectionClock())) return null
        val firstControls = BrawlerLobbyReturnGate.uniqueControls(first, plan) ?: return null
        val secondControls = BrawlerLobbyReturnGate.uniqueControls(second, plan) ?: return null
        if (!BrawlerLobbyReturnGate.agree(second.height, firstControls, secondControls)) return null
        val windows = BrawlerLobbyReturnGate.controlWindows(second.width, second.height, secondControls)
            ?: return null
        // Animation of the lobby character is outside every window; a moving control is inside one.
        if (!BrawlerLobbyReturnGate.controlsStill(pair.firstFrame, pair.secondFrame, windows)) return null
        if (stateOf(pair.firstFrame) != "lobby" || stateOf(pair.secondFrame) != "lobby") return null
        if (!BrawlerLobbyReturnGate.distinctFreshPair(first, second, selectionClock())) return null
        return secondControls
    }

    /**
     * The post-SELECT handoff, called ONLY in the same [selectBrawler] stack, immediately AFTER the
     * committed and live-gated DETAIL SELECT click.
     *
     * It observes and publishes; it never taps PLAY, never re-taps SELECT and never resends any
     * gesture. `StageManager.startGame()` remains the only PLAY trigger, so a `true` result flows
     * into the unchanged BotEngine/StageManager success path.
     *
     * What is intentionally replaced: the previous rule demanded the requested brawler's own name
     * to be readable in the returned lobby. The native replay of the actual returned lobby
     * (`native-baseline/RESULT.json`) reads zero target-name boxes, so that name is not rendered and
     * its absence is not evidence of anything. The replacement is a strictly causal binding - this
     * handoff is only reachable from a committed SELECT on a uniquely confirmed target detail
     * identity - combined with positive, unique, stable and freshly re-checked lobby controls.
     *
     * Bounded inside the existing lobby phase budget: [notBefore] and [deadline] are the caller's,
     * the budget is never renewed, and each rejection returns a fixed token so any future stop
     * stays attributable to the boundary that produced it.
     */
    private fun verifyLobbyReturn(notBefore: Long, deadline: Long): Boolean {
        lobbyReturnOcrReuse.clear()
        val phaseDeadline = minOf(deadline, selectionClock() + PHASE_TIMEOUT_MS)
        // The returned lobby animates its centre permanently, so the whole-frame stability lattice
        // is never used here: freshness and distinctness of the two captures carry the timing, and
        // stability is decided later over bounded per-control windows only.
        fun freshSince(earliest: Long): FrameSnapshot? {
            while (allowed(phaseDeadline)) {
                val frame = freshFrame()
                if (frame != null && frame.capturedAtMs >= earliest) return frame
                if (!waitStep(SCAN_INTERVAL_MS, phaseDeadline)) return null
            }
            return null
        }
        var next = notBefore
        while (allowed(phaseDeadline)) {
            if (cancelled()) {
                noteLobbyReturn(LobbyReturnReason.EVIDENCE_EXPIRED)
                return false
            }
            val firstFrame = freshSince(next)
            if (firstFrame == null) break
            val budget = minOf(15_000L, (phaseDeadline - selectionClock()).coerceAtLeast(1L))
            val first = try {
                extractLobbyReturnTextAndPositions(firstFrame, budget)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (stateOf(firstFrame) != "lobby") {
                noteLobbyReturn(LobbyReturnReason.LOBBY_STATE_UNCONFIRMED)
                if (!waitStep(SCAN_INTERVAL_MS, phaseDeadline)) break
                next = selectionClock()
                continue
            }
            if (!waitStep(SCAN_INTERVAL_MS, phaseDeadline)) break
            val secondFrame = freshSince(firstFrame.capturedAtMs + SCAN_INTERVAL_MS)
            if (secondFrame == null) break
            val second = try {
                extractLobbyReturnTextAndPositions(
                    secondFrame,
                    minOf(15_000L, (phaseDeadline - selectionClock()).coerceAtLeast(1L)),
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (stateOf(secondFrame) != "lobby") {
                noteLobbyReturn(LobbyReturnReason.LOBBY_STATE_UNCONFIRMED)
                if (!waitStep(SCAN_INTERVAL_MS, phaseDeadline)) break
                next = selectionClock()
                continue
            }
            val pair = ScanPair(firstFrame, secondFrame, first, second)
            // Verifying consumed real time, so the whole pair is re-checked for freshness AFTER it.
            if (!pairStillFresh(pair)) {
                noteLobbyReturn(LobbyReturnReason.EVIDENCE_EXPIRED)
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            val controls = confirmedLobbyReturn(pair)
            if (controls == null) {
                noteLobbyReturn(LobbyReturnReason.CONTROLS_UNCONFIRMED)
                if (!waitStep(SCAN_INTERVAL_MS, phaseDeadline)) break
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            if (!pairStillFresh(pair)) {
                noteLobbyReturn(LobbyReturnReason.EVIDENCE_EXPIRED)
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            // Final live frame: positive lobby state AND both observed control windows unchanged.
            val live = freshFrame()
            val liveWindows = live?.let {
                BrawlerLobbyReturnGate.controlWindows(it.width, it.height, controls)
            }
            val liveOk = live != null && liveWindows != null &&
                live.capturedAtMs >= secondFrame.capturedAtMs &&
                stateOf(live) == "lobby" &&
                BrawlerLobbyReturnGate.controlsStill(secondFrame, live, liveWindows) &&
                pairStillFresh(pair) && allowed(phaseDeadline) && !cancelled()
            if (!liveOk) {
                noteLobbyReturn(LobbyReturnReason.LIVE_CONTROL_UNCONFIRMED)
                // SELECT is already committed: a final live denial remains terminal.
                return false
            }
            return true
        }
        noteLobbyReturn(LobbyReturnReason.EVIDENCE_EXPIRED)
        return false
    }

    private data class GridAdmission(val pair: ScanPair?, val reason: GridReason,
        val strongIdentity: Boolean = false)

    /** True while this admitted pair is still new enough to act on. */
    private fun pairStillFresh(pair: ScanPair): Boolean =
        selectionClock() - pair.first.capturedAtMs <= FRAME_MAX_AGE_MS &&
            selectionClock() - pair.second.capturedAtMs <= FRAME_MAX_AGE_MS

    /**
     * A single OCR pair is not proof that the list is readable. The first pair after the menu opens
     * can be partial - the header without two known card names, or only one of the two OCR frames
     * populated - and the readable evidence follows it. Treating the first pair as final aborted
     * selection before any target matching, scrolling or verification ever ran.
     *
     * This admits the grid only from a pair that is itself verified as readable and still fresh,
     * and rescans while the pair is unsettled, its evidence is no longer fresh, or the grid
     * identity is still incomplete. It adds no gesture: no repeat menu request, no blind scroll, no
     * widened region, no lowered OCR/name geometry threshold, and no bypass for a moving or
     * conflicting target. Recovery never renews the 15 s page deadline or the finite overall
     * selection deadline; only an authorized paging request starts a new page budget.
     */
    private fun admitGrid(notBefore: Long, phaseDeadline: Long, deadline: Long,
        listSession: BrawlerListSearchSession? = null): GridAdmission {
        var next = notBefore
        var attempt = 0
        while (true) {
            if (cancelled()) {
                noteGrid(GridReason.CANCELLED)
                return GridAdmission(null, GridReason.CANCELLED)
            }
            if (selectionClock() >= phaseDeadline || selectionClock() >= deadline) {
                noteGrid(GridReason.PHASE_TIMEOUT)
                return GridAdmission(null, GridReason.PHASE_TIMEOUT)
            }
            if (attempt >= MAX_GRID_ADMIT_ATTEMPTS) {
                noteGrid(GridReason.ATTEMPT_LIMIT)
                return GridAdmission(null, GridReason.ATTEMPT_LIMIT)
            }
            attempt++
            BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_GRID, false)
            val pair = settledPair(next, ScanRegion.GRID, phaseDeadline, listSession)
            if (pair == null) {
                noteGrid(GridReason.PAIR_UNSETTLED)
                // The rescan stopped because the run was cancelled, not because time ran out.
                if (cancelled()) {
                    noteGrid(GridReason.CANCELLED)
                    return GridAdmission(null, GridReason.CANCELLED)
                }
                if (!allowed(phaseDeadline) || selectionClock() >= phaseDeadline) {
                    noteGrid(GridReason.PHASE_TIMEOUT)
                    return GridAdmission(null, GridReason.PHASE_TIMEOUT)
                }
                // Rescan from now: the settled-pair attempt already consumed real time.
                next = selectionClock()
                continue
            }
            // Cheap pre-check: do not spend identity work on evidence that already aged out.
            if (!pairStillFresh(pair)) {
                noteGrid(GridReason.FRESHNESS_REJECTED)
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            val continued = listSession?.confirms(pair.firstFrame, pair.secondFrame, selectionClock()) == true
            // No sort-caption OCR is observed or required here: the caption never decides a page.
            val strongIdentity = !continued && inGrid(pair)
            val gridReady = continued || strongIdentity
            // Verifying identity consumed part of the budget, so freshness is re-checked AFTER it:
            // a pair admitted on stale evidence must be rescanned, never acted on.
            if (!pairStillFresh(pair)) {
                noteGrid(GridReason.FRESHNESS_REJECTED)
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            if (!gridReady) {
                noteGrid(GridReason.IDENTITY_INCOMPLETE)
                next = pair.second.capturedAtMs + SCAN_INTERVAL_MS
                continue
            }
            val reason = if (continued) GridReason.SESSION_CONFIRMED else GridReason.ADMITTED
            noteGrid(reason)
            BotStatus.publish(RuntimeState.BRAWLER_LIST, VerificationPhase.VERIFY_GRID, true)
            return GridAdmission(pair, reason, strongIdentity)
        }
    }

    private fun openBrawlerMenu(deadline: Long): Boolean {
        val entryDeadline = minOf(deadline, selectionClock() + PHASE_TIMEOUT_MS)
        var sawMenuLabel = false
        fun note(reason: String) = DiagnosticLog.event("selection.entry", "reason=$reason")
        fun retry(reason: String): Boolean {
            note(reason)
            val more = waitStep(SCAN_INTERVAL_MS, entryDeadline)
            // A denied retry is the entry gate's exhaustion boundary, never a silent break.
            // [reason] is a fixed literal vocabulary; no OCR, coordinates or timings.
            if (!more) DiagnosticLog.failure("entry", "brawler_menu", "retry_denied_$reason")
            return more
        }
        fun current(frame: FrameSnapshot): Boolean {
            if (!allowed(entryDeadline)) return false
            val now = selectionClock()
            return frame.capturedAtMs > 0L && frame.capturedAtMs <= now &&
                now - frame.capturedAtMs <= FRAME_MAX_AGE_MS
        }
        fun scan(frame: FrameSnapshot): BrawlerOcrScan? = try {
            if (!current(frame)) null
            else extractTextAndPositions(frame, minOf(15_000L, entryDeadline - selectionClock()))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (t: Exception) {
            DiagnosticLog.failure("entry", "brawler_menu", "menu_ocr_exception", t)
            null
        }
        fun observe(scan: BrawlerOcrScan) {
            // Invalid or ambiguous label evidence must not be turned into coordinate permission.
            if (scan.hits.any { cleanName(it.text) in BRAWLERS_MENU_LABELS }) sawMenuLabel = true
        }

        while (allowed(entryDeadline)) {
            val firstFrame = freshFrame()
            if (firstFrame == null) {
                if (!retry("frame_unavailable")) break
                continue
            }
            val firstState = stateOf(firstFrame)
            if (firstState == "brawler_selection" && current(firstFrame)) {
                note("list_already_open")
                return current(firstFrame)
            }
            if (firstState != "lobby" || !current(firstFrame)) {
                if (!retry("lobby_unconfirmed")) break
                continue
            }
            val first = scan(firstFrame)
            if (first == null) {
                if (!retry("menu_ocr_unavailable")) break
                continue
            }
            observe(first)
            if (!current(firstFrame)) {
                if (!retry("evidence_expired")) break
                continue
            }
            if (!waitStep(SCAN_INTERVAL_MS, entryDeadline)) break
            val secondFrame = freshFrame()
            if (secondFrame == null || secondFrame.capturedAtMs <= firstFrame.capturedAtMs) {
                if (!retry("distinct_frame_unavailable")) break
                continue
            }
            if (stateOf(secondFrame) != "lobby" || !current(secondFrame)) {
                if (!retry("lobby_changed")) break
                continue
            }
            val second = scan(secondFrame)
            if (second == null) {
                if (!retry("menu_ocr_unavailable")) break
                continue
            }
            observe(second)
            if (!current(firstFrame) || !current(secondFrame)) {
                if (!retry("evidence_expired")) break
                continue
            }
            val label = BrawlerSelectionGate.confirmPair(
                first, second, BRAWLERS_MENU_LABELS, 0.15, 1.0, selectionClock(),
            )
            val x: Float
            val y: Float
            val bounds: LobbyMenuEntryGate.Bounds?
            if (label != null) {
                x = label.centerX.toFloat(); y = label.centerY.toFloat()
                bounds = LobbyMenuEntryGate.aroundLabel(second.width, second.height, label)
            } else if (sawMenuLabel) {
                if (!retry("menu_label_unconfirmed")) break
                continue
            } else {
                // Retain the existing opening-only point, never as a card/Select fallback.
                val btn = lobbyConfig().getIntArray("lobby.brawler_btn")
                val hr = com.pyla.ai.capture.UiLayout.unit(second.width, second.height).toFloat()
                x = (if (btn.size >= 2) btn[0] else 110) * hr
                y = com.pyla.ai.capture.UiLayout.y((if (btn.size >= 2) btn[1] else 490).toDouble(), second.width, second.height).toFloat()
                bounds = LobbyMenuEntryGate.aroundPoint(second.width, second.height, x, y)
            }
            if (bounds == null) {
                note("menu_point_invalid")
                return false
            }
            fun controlStill(a: FrameSnapshot, b: FrameSnapshot): Boolean =
                a.width == b.width && a.height == b.height &&
                    LobbyMenuEntryGate.pixelsStill(a.width, a.height, a.argb, b.argb, bounds)
            if (!controlStill(firstFrame, secondFrame)) {
                if (!retry("menu_control_moving")) break
                continue
            }
            val live = freshFrame()
            if (live == null || live.capturedAtMs < secondFrame.capturedAtMs ||
                stateOf(live) != "lobby" || !controlStill(secondFrame, live) ||
                !current(firstFrame) || !current(secondFrame) || !current(live)) {
                if (!retry("live_control_unconfirmed")) break
                continue
            }
            // Log intent, then recheck authority/age so logger latency cannot extend evidence.
            note(if (label != null) "menu_open_requested_ocr" else "menu_open_requested_legacy_point")
            if (!current(firstFrame) || !current(secondFrame) || !current(live)) break
            windowController.click(x, y)
            return true // One request only; the unchanged downstream pair must prove the actual list.
        }
        // No additional authority/clock invocation merely to classify a diagnostic.
        val entryStop = "entry_unconfirmed"
        note(entryStop)
        DiagnosticLog.failure("entry", "brawler_menu", entryStop)
        return false
    }

    fun selectBrawler(brawler: String): String {
        val target = cleanName(brawler)
        val deadline = selectionClock() + SELECTION_TIMEOUT_MS
        // Exactly one never-folded terminal line per invocation, including these pre-claim guards.
        if (target.isEmpty()) return selectTerminal("error", "target_name_empty")
        if (!allowed(deadline)) return selectTerminal("error", "selection_not_allowed")
        var terminalResult = "error"
        var terminalReason = "not_started"
        fun finish(result: String, reason: String): String {
            terminalResult = result
            terminalReason = reason
            return result
        }
        fun phase(result: String, region: ScanRegion, reason: String): String {
            terminalResult = result
            terminalReason = reason
            return notePhaseTerminal(result, region, reason)
        }
        // The selector owns stronger OCR state evidence for its whole bounded phase, so the
        // background classifier cannot overwrite it. The claim is taken BEFORE the first status
        // publication, and released in finally on every exit path.
        BotStatus.selectionActive = true
        BotStatus.action("Selecting brawler: $target")
        return try {
            val initial = windowController.screenshot()
            selectionWidth = initial.width; selectionHeight = initial.height
            val names = targetNames(target)
            BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.OPEN_MENU, false)
            if (names.isEmpty()) return finish("stuck", "target_name_unresolved")
            if (!openBrawlerMenu(deadline)) return finish("stuck", "menu_entry_unconfirmed")
            var notBefore = selectionClock() + OPEN_SETTLE_MS
            var page = 0
            var listSession: BrawlerListSearchSession? = null
            var emptyNameRetries = 0
            var phaseDeadline = minOf(deadline, selectionClock() + PHASE_TIMEOUT_MS)
            // Scan the initial page AND the page reached by the final permitted swipe.
            // The gesture cap limits requests, not the number of admitted pages we can inspect.
            while (page <= MAX_SCROLL_PAGES) {
                val admission = admitGrid(notBefore, phaseDeadline, deadline, listSession)
                val admitted = admission.pair
                if (admitted == null) return finish("stuck", admission.reason.token)
                val grid: ScanPair = admitted
                if (admission.strongIdentity) {
                    // Passive observer: names the real predicate behind a null session, adds nothing.
                    var sessionRejection: BrawlerListSearchSession.Rejection? = null
                    val sessionObserver = BrawlerListSearchSession.CaptureObserver {
                        sessionRejection = it
                    }
                    listSession = BrawlerListSearchSession.capture(grid.firstFrame, grid.secondFrame,
                        grid.first, grid.second, selectionClock(), initialListConfirmed = true,
                        observer = sessionObserver)
                    if (!allowed(phaseDeadline)) {
                        DiagnosticLog.failure("selection", "list_session", "phase_not_allowed")
                        notBefore = selectionClock() + SCAN_INTERVAL_MS
                        continue
                    }
                    if (!pairStillFresh(grid)) {
                        DiagnosticLog.failure("selection", "list_session", "pair_stale_after_admission")
                        notBefore = selectionClock() + SCAN_INTERVAL_MS
                        continue
                    }
                    DiagnosticLog.event("selection.search", if (listSession != null)
                        "reason=list_session_started" else "reason=list_session_unavailable")
                    // The concrete existing rejection code, never the generic channel name alone.
                    if (listSession == null) DiagnosticLog.failure("selection", "list_session",
                        sessionRejection?.code ?: "list_session_unobserved")
                }
                BotStatus.publish(RuntimeState.BRAWLER_LIST, VerificationPhase.SCAN_NAMES, true)
                val hit = confirmed(grid, names, GRID_TOP, GRID_BOTTOM)
                if (hit != null) {
                    // Status/diagnostics precede the last live check, not the input it authorises.
                    BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_DETAIL, false)
                    // The pre-tap live evidence is bound to the OBSERVED target card only: the list
                    // around it animates (right preview, banner), this label does not. An unlocalisable
                    // target box yields no window, and the gate then keeps the stricter whole-area rule.
                    val targetWindows = BrawlerGridTargetStability.windows(
                        grid.second.width, grid.second.height, hit)
                    val denial = liveGate(grid, ScanRegion.GRID, phaseDeadline, targetWindows)
                    if (denial != null) {
                        if (liveTerminal(denial)) return finish("stuck", denial.token)
                        // No page/gesture/deadline is consumed or renewed. Both retained captures
                        // are discarded; the next iteration must admit and match an entirely new pair.
                        notBefore = maxOf(selectionClock(), grid.second.capturedAtMs) + SCAN_INTERVAL_MS
                        BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_GRID, false)
                        continue
                    }
                    // The observed label belongs to this stable card: click inside its bounds, no offset.
                    windowController.click(hit.centerX.toFloat(), hit.centerY.toFloat())
                    val detailDeadline = minOf(deadline, selectionClock() + PHASE_TIMEOUT_MS)
                    val select = when (val detail = confirmDetailSelect(names, deadline, detailDeadline)) {
                        is DetailConfirm.Selected -> detail.hit
                        DetailConfirm.Unsettled -> return phase("stuck", ScanRegion.DETAIL, "detail_pair_unsettled")
                        DetailConfirm.Rejected -> return phase("failed", ScanRegion.DETAIL, "detail_identity_incomplete")
                        DetailConfirm.Denied -> return finish("failed", "detail_denied")
                    }
                    windowController.click(select.centerX.toFloat(), select.centerY.toFloat())
                    BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_SELECTION, false)
                    // Post-SELECT handoff, in this same stack and immediately after the committed
                    // SELECT above: positive, unique, stable and freshly re-checked lobby controls
                    // observed twice. It never taps PLAY or SELECT; StageManager still starts the
                    // match after an unchanged success result.
                    if (!verifyLobbyReturn(selectionClock() + OPEN_SETTLE_MS, deadline)) {
                        return phase("stuck", ScanRegion.LOBBY, "lobby_return_unconfirmed")
                    }
                    BotStatus.publish(RuntimeState.LOBBY, VerificationPhase.VERIFIED, true)
                    BotStatus.action("Verified selected brawler: $target")
                    Log.i(TAG, "Brawler selection identity verified")
                    return finish("success", "selection_verified")
                }
                // A visible but conflicting/duplicate/moving target must be rescanned, never clicked or skipped.
                val visibleTarget = (grid.first.hits + grid.second.hits).any {
                    cleanName(it.text) in names && it.top >= grid.second.height * GRID_TOP && it.bottom <= grid.second.height * GRID_BOTTOM
                }
                if (visibleTarget) {
                    // Bounded inside the SAME page verification budget: rescan only, no click, no skip.
                    if (selectionClock() >= phaseDeadline) {
                        DiagnosticLog.failure("selection", "scan", "phase_timeout")
                        return finish("stuck", "phase_timeout")
                    }
                    notBefore = maxOf(selectionClock(), grid.second.capturedAtMs) + SCAN_INTERVAL_MS
                    continue
                }
                // Retry empty OCR on this SAME page before moving on. Missing names do not revoke
                // fresh fixed-list-control evidence, and retries never renew the page deadline.
                val anyKnownName = (grid.first.hits + grid.second.hits).any {
                    it.top >= grid.second.height * GRID_TOP && it.bottom <= grid.second.height * GRID_BOTTOM &&
                        cleanName(it.text) in knownMenuNames
                }
                if (!anyKnownName && listSession != null && emptyNameRetries < 2) {
                    emptyNameRetries++
                    DiagnosticLog.event("selection.search", "reason=empty_names_retry")
                    notBefore = maxOf(selectionClock(), grid.second.capturedAtMs) + SCAN_INTERVAL_MS
                    continue
                }
                if (page == MAX_SCROLL_PAGES) {
                    // Exhausting a safety cap is not proof that the physical list ended.
                    DiagnosticLog.event("selection.search", "reason=page_limit")
                    break
                }
                val right = grid.second.width * 0.90f
                val left = grid.second.width * 0.60f
                val row = grid.second.height * 0.55f
                BotStatus.publish(RuntimeState.BRAWLER_LIST, VerificationPhase.SCROLL_GRID, true)
                val swipeDenial = listScrollGate(grid, phaseDeadline, listSession)
                if (swipeDenial != null) {
                    if (liveTerminal(swipeDenial)) return finish("stuck", swipeDenial.token)
                    notBefore = maxOf(selectionClock(), grid.second.capturedAtMs) + SCAN_INTERVAL_MS
                    BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_GRID, false)
                    continue
                }
                // User-confirmed layout: further columns are to the right. Drag right -> left;
                // both endpoints share exactly the same y, and the next page requires fresh scans.
                windowController.swipe(right, row, left, row, SCROLL_DURATION_MS)
                page++ // Count only actual paging requests, never denied checks or conflict rescans.
                emptyNameRetries = 0
                notBefore = selectionClock() + SCROLL_DURATION_MS + SCROLL_SETTLE_MS
                phaseDeadline = minOf(deadline, selectionClock() + PHASE_TIMEOUT_MS)
            }
            BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.TIMEOUT, false)
            finish("failed", "page_exhausted")
        } catch (t: Throwable) {
            // No raw OCR, frame content or device details in diagnostics: only the exception TYPE.
            Log.w(TAG, "Brawler selection aborted: scan or capture unavailable")
            DiagnosticLog.failure("selection", "select", "unexpected_exception", t)
            finish("error", "unexpected_exception")
        } finally {
            // Always release the selector's claim, even on the success and error exits.
            BotStatus.selectionActive = false
            // One unambiguous, never-folded terminal per invocation; nonthrowing by construction.
            try { DiagnosticLog.terminal("selection", "select", terminalResult, terminalReason) }
            catch (_: Throwable) { }
        }
    }

    fun close() {
        lobbyReturnOcrReuse.clear()
        if (recognizerDelegate.isInitialized()) {
            try { recognizerDelegate.value.close() } catch (_: Throwable) {}
        }
        sourceBitmap?.recycle()
        sourceBitmap = null
        scaledOcrBitmap?.recycle()
        scaledOcrBitmap = null
        gridOcrBitmap?.recycle()
        gridOcrBitmap = null
        lobbyReturnBitmap?.recycle()
        lobbyReturnBitmap = null
    }

    companion object {
        private const val TAG = "PylaLobby"
        private const val FLAT_DIALOG_BRIGHTNESS_SPREAD = 8.0
        private const val FRAME_MAX_AGE_MS = 3_000L
        private const val MAX_SCROLL_PAGES = 40
        private const val PHASE_TIMEOUT_MS = 15_000L
        // One initial grid page + one per swipe, plus menu entry, detail and lobby.
        // This finite outer cap lets the last permitted page be searched even when earlier
        // pages used most of their budgets; an unreadable/denied page still ends in 15 s.
        private const val SELECTION_TIMEOUT_MS = (MAX_SCROLL_PAGES + 4L) * PHASE_TIMEOUT_MS
        private const val OPEN_SETTLE_MS = 1_250L
        private const val SCROLL_SETTLE_MS = 2_500L
        private const val SCAN_INTERVAL_MS = 250L
        private const val SCROLL_DURATION_MS = 500L
        /**
         * Bounded fresh-rescan budget for grid admission. Every attempt costs real time - each
         * rescan waits at least one SCAN_INTERVAL_MS between fresh captures, and a full attempt
         * costs several - so this cap is never the binding constraint on the 15 s phase deadline:
         * the phase deadline still ends admission first. The cap only stops a path that made no
         * clock progress at all, so it cannot extend the time budget.
         */
        private const val MAX_GRID_ADMIT_ATTEMPTS = 60
        /**
         * Bounded fresh-reobservation budget for the DETAIL confirmation below the committed card
         * click. Each retry re-observes a full settled pair (>= 2 fresh captures plus intervals), so
         * this cap is not the binding constraint either: the caller's finite detail phase deadline
         * still ends the detail phase first. It only stops a path that made no clock progress.
         */
        private const val MAX_DETAIL_CONFIRM_ATTEMPTS = 3
        /** Fresh control pairs tried before a missing/unfit detail identity becomes terminal. */
        private const val MAX_DETAIL_IDENTITY_ATTEMPTS = 6
        /**
         * Bounded fresh-observation budget for the DETAIL control pair. Each attempt costs real
         * time (>= 2 fresh captures plus intervals), so the caller's finite detail phase deadline
         * still ends the phase first; this cap only stops a path that made no clock progress.
         */
        private const val MAX_DETAIL_PAIR_ATTEMPTS = 60
        private const val GRID_TOP = 0.16
        private const val GRID_BOTTOM = 0.91
        private const val DETAIL_TOP = 0.08
        private const val DETAIL_BOTTOM = 0.65
        private val BRAWLERS_MENU_LABELS = setOf("brawlers", "brawler", "lottatori")
        private val SELECT_BUTTON_LABELS = setOf("select", "seleziona", "scegli", "auswahlen", "seleccionar", "selectionner")
    }
}
