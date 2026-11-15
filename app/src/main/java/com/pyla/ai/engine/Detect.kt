package com.pyla.ai.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.diagnostics.PipelineTimingTelemetry
import com.pyla.ai.diagnostics.PipelineTimingTelemetry.Stage
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.max

class Detect(
    modelPath: String,
    val classes: List<String>? = null,
    val inputSize: Pair<Int, Int> = 640 to 640,
    threads: Int = configuredThreads(),
    /** Observation-only label for diagnostics; never read back by detection. */
    private val telemetryRole: String = PerceptionPerformanceTelemetry.ROLE_OTHER,
    /**
     * Explicit declaration of the pinned upstream semantic terrain variant (tile classes +
     * per-pixel class/confidence rasters). Only this flag selects the decoder: the sidecar
     * `<model>.json` next to the resolved model file must exist, parse and match the model IO.
     * Missing/malformed metadata throws; it never falls back to the legacy box decoder.
     */
    private val semanticTerrain: Boolean = false,
    /**
     * `perceived_tile_size` in original capture pixels (upstream `tile_size`, default 54). Used
     * only by the semantic per-cell geometry; ignored by the legacy box decoder.
     */
    private val terrainTileSize: Double = 54.0,
    /**
     * Measure thread counts and providers on this device instead of trusting fixed heuristics.
     * Only honoured while both `cpu_or_gpu` and `used_threads` are "auto"; an explicit user
     * setting always wins. Any measurement problem falls back to the previous fixed selection.
     */
    private val autoTune: Boolean = false,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val preferredDevice: String = PylaConfig.load("cfg/general_config.toml")
        .getString("cpu_or_gpu", "auto").trim().lowercase()
    private val modelFile: File
    private var session: OrtSession
    private var providerName: String
    private var inputName: String
    private val inputH: Int
    private val inputW: Int
    /** Legacy YOLO letterbox pad; the semantic variant uses 114/255 centred (see helper). */
    private val padValue: Float = 128f / 255f
    /** Non-null only when [semanticTerrain] was declared and manifest/model IO validated. */
    private var semanticRuntime: SemanticTerrainRuntime? = null
    private var configuredThreadCount = threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    /** Steady-state inference time measured while tuning, or null when tuning did not run. */
    var tunedMedianMs: Long? = null
        private set
    /** Observation only: stage triple for this model's declared role; never read by detection. */
    private val timingStages: TimingStages = TimingStages.forRole(telemetryRole)
    /** Observation only: the EP the session factory intended to build, before any fallback. */
    private var requestedProviderName: String = "AUTO"

    init {
        try { org.opencv.core.Core.setNumThreads(configuredThreadCount) } catch (_: Throwable) {}
        modelFile = resolveModel(modelPath)
        // Manifest lives beside the *resolved* file, which may be an immutable terrain generation
        // directory. Never look in the legacy root/models slot and never continue without it.
        val manifest = if (semanticTerrain) loadSemanticManifest(modelFile) else null
        inputH = manifest?.height ?: inputSize.first
        inputW = manifest?.width ?: inputSize.second
        // Device-measured threads/provider when everything is on "auto"; otherwise, or when
        // nothing could be measured, the original fixed selection below is used unchanged.
        val tuned = if (autoTune && preferredDevice == "auto") SessionTuning.tune(env, modelFile, padValue, isEmulator()) else null
        if (tuned != null) { configuredThreadCount = tuned.threads; tunedMedianMs = tuned.medianMs }
        val loaded = tuned?.let { it.session to it.provider } ?: createSessionWithFallback(modelFile, configuredThreadCount)
        session = loaded.first
        providerName = loaded.second
        inputName = session.inputNames.first()
        try {
            if (manifest != null) validateSemanticSession(manifest)
        } catch (t: Throwable) {
            try { session.close() } catch (_: Throwable) {}
            throw t
        }
        semanticRuntime = manifest?.let { SemanticTerrainRuntime(it, terrainTileSize) }
        Log.i(
            TAG,
            "Loaded ${modelFile.name} provider=$providerName variant=${if (manifest != null) "semantic-terrain" else "legacy-box"} " +
                "inputs=${session.inputNames} outputs=${session.outputNames}",
        )
        // Observation only: bounded provider/thread shape, no path, device or model identifiers.
        PerceptionPerformanceTelemetry.recordBuild()
        PerceptionPerformanceTelemetry.recordModel(telemetryRole, providerName, configuredThreadCount)
        // Requested vs actually created provider after the constructor's own fallback chain.
        observe {
            PipelineTimingTelemetry.model(
                telemetryRole, requestedProviderName, providerName, configuredThreadCount,
            )
        }
    }

    /** Effective runtime provider; configuration observation, not proof of hardware offload. */
    val activeProvider: String get() = providerName

    /** Configured intra-op thread count, not measured utilization or achieved parallelism. */
    val activeThreads: Int get() = configuredThreadCount

    /**
     * True only when the semantic variant was declared and its manifest/model IO validated.
     * Callers must branch on this instead of inferring the variant from class names or outputs.
     */
    val isSemanticTerrain: Boolean get() = semanticRuntime != null

    /** Output class order of this decoder: terrain order for semantic, configured otherwise. */
    val effectiveClasses: List<String>
        get() = semanticRuntime?.let { TerrainTileClasses.ORDER } ?: classes.orEmpty()

    private fun baseOptions(threads: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Models are executed sequentially. Multiple inter-op pools only oversubscribe low-end CPUs.
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads)
    }

    private fun createSessionWithFallback(f: File, threads: Int): Pair<OrtSession, String> {
        // Observation only: the provider this call intends to build, before any fallback happens.
        // Same short-circuit order as the original condition, so no extra isEmulator() probe runs.
        val nnapiAttempted = preferredDevice != "cpu" && !isEmulator()
        requestedProviderName = if (nnapiAttempted) "NNAPI" else "XNNPACK"
        if (nnapiAttempted) {
            val opts = baseOptions(threads)
            try {
                Log.i(TAG, "Creating session for ${f.name} with NNAPI")
                opts.addNnapi()
                return env.createSession(f.absolutePath, opts) to "NNAPI"
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI session failed for ${f.name} (${t.message}); trying XNNPACK")
            } finally {
                opts.close()
            }
        } else if (preferredDevice == "cpu") {
            Log.i(TAG, "cpu_or_gpu=cpu, skipping NNAPI for ${f.name}")
        } else {
            Log.i(TAG, "Emulator/x86 detected, skipping NNAPI for ${f.name}")
        }
        return createXnnpackOrCpu(f, threads)
    }

    private fun createXnnpackOrCpu(f: File, threads: Int): Pair<OrtSession, String> {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            opts.setInterOpNumThreads(1)
            opts.setIntraOpNumThreads(1)
            val loaded = env.createSession(f.absolutePath, opts)
            Log.i(TAG, "Using XNNPACK EP for ${f.name} ($threads threads)")
            return loaded to "XNNPACK"
        } catch (t: Throwable) {
            Log.w(TAG, "XNNPACK session failed for ${f.name} (${t.message}); falling back to CPU")
        } finally {
            opts.close()
        }
        return createCpuSession(f, threads)
    }

    private fun createCpuSession(f: File, threads: Int): Pair<OrtSession, String> {
        val opts = baseOptions(threads)
        return try {
            Log.i(TAG, "Creating session for ${f.name} with CPU")
            env.createSession(f.absolutePath, opts) to "CPU"
        } finally {
            opts.close()
        }
    }

    private fun resolveModel(path: String): File {
        val candidates = listOf(File(path), PylaConfig.resolve(path.removePrefix("./").removePrefix("/")),
            File(PylaConfig.root(), "models/${File(path).name}"))
        candidates.firstOrNull { it.exists() }?.let { return it }
        val wanted = File(path).name
        File(PylaConfig.root(), "models").listFiles()
            ?.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
        throw IllegalStateException("Model not found: $path")
    }

    // ---- Observation-only timing seams ------------------------------------------------
    // Every call below is a bounded side channel: a throwing clock, sink or formatter must never
    // change a return value, an exception or a cleanup step of detection.

    /** Total observation wrapper: diagnostics failures are swallowed, never propagated. */
    private fun observe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }

    /**
     * Diagnostic monotonic start in ms, or -1 when the facade clock is unavailable. Uses the
     * telemetry facade's own guarded clock so no additional authoritative clock is read here.
     */
    private fun diagnosticStart(): Long = try { PipelineTimingTelemetry.nowMs() } catch (_: Throwable) { -1L }

    /**
     * Record one measured span. A missing/invalid start reads nothing at all: no zero sample is
     * fabricated, and a facade that reports a negative value is ignored rather than clamped.
     */
    private fun recordStage(stage: Stage, startedAtMs: Long) {
        if (startedAtMs < 0L) return
        observe {
            PipelineTimingTelemetry.elapsed(stage, startedAtMs)
        }
    }

    /** Role-selected stage triple. Semantic and legacy tile paths share the terrain family. */
    private class TimingStages(val preprocess: Stage, val inference: Stage, val decode: Stage) {
        companion object {
            fun forRole(role: String): TimingStages = when (role) {
                PerceptionPerformanceTelemetry.ROLE_TILE,
                PerceptionPerformanceTelemetry.ROLE_TILE_CENTERED ->
                    TimingStages(Stage.TERRAIN_PREPROCESS, Stage.TERRAIN_INFERENCE, Stage.TERRAIN_DECODE)
                else ->
                    TimingStages(Stage.ENTITY_PREPROCESS, Stage.ENTITY_INFERENCE, Stage.ENTITY_DECODE)
            }
        }
    }

    private fun preprocess(img: Mat): Triple<FloatBuffer, Int, Int> {
        val h = img.rows(); val w = img.cols()
        val scale = minOf(inputH.toDouble() / h, inputW.toDouble() / w)
        val nw = (w * scale).toInt(); val nh = (h * scale).toInt()
        val resized = Mat()
        Imgproc.resize(img, resized, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val planeSize = inputH * inputW
        val scratch = preprocessScratch.get()
        val byteCount = nw * nh * 3
        val bytes = scratch.bytes(byteCount)
        val floats = scratch.floats(3 * planeSize)
        resized.get(0, 0, bytes)
        resized.release()
        Arrays.fill(floats, 0, 3 * planeSize, padValue)

        var y = 0
        while (y < nh) {
            var x = 0
            while (x < nw) {
                val s = (y * nw + x) * 3
                val d = y * inputW + x
                floats[d] = (bytes[s].toInt() and 0xFF) / 255f
                floats[planeSize + d] = (bytes[s + 1].toInt() and 0xFF) / 255f
                floats[2 * planeSize + d] = (bytes[s + 2].toInt() and 0xFF) / 255f
                x++
            }
            y++
        }
        return Triple(FloatBuffer.wrap(floats, 0, 3 * planeSize), nw, nh)
    }

    /**
     * Semantic terrain preprocessing: aspect-preserving resize then *centred* letterbox filled
     * with 114/255, mirroring upstream `Detect.preprocess_image` for semantic/end2end models.
     * The legacy path above deliberately keeps truncating resize + top-left 128/255.
     */
    private fun preprocessSemantic(img: Mat): Pair<FloatBuffer, SemanticTerrainLetterbox.Geometry> {
        val geometry = SemanticTerrainLetterbox.compute(img.cols(), img.rows(), inputW, inputH)
        val resized = Mat()
        Imgproc.resize(
            img, resized,
            Size(geometry.contentWidth.toDouble(), geometry.contentHeight.toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR,
        )
        val scratch = semanticScratch.get()
        val contentBytes = geometry.contentWidth * geometry.contentHeight * 3
        val bytes = scratch.bytes(contentBytes)
        resized.get(0, 0, bytes)
        resized.release()

        val planeSize = inputH * inputW
        val floats = scratch.floats(3 * planeSize)
        Arrays.fill(floats, 0, 3 * planeSize, SemanticTerrainLetterbox.PAD_VALUE)
        var y = 0
        while (y < geometry.contentHeight) {
            var x = 0
            while (x < geometry.contentWidth) {
                val s = (y * geometry.contentWidth + x) * 3
                val d = (geometry.padY + y) * inputW + geometry.padX + x
                floats[d] = (bytes[s].toInt() and 0xFF) / 255f
                floats[planeSize + d] = (bytes[s + 1].toInt() and 0xFF) / 255f
                floats[2 * planeSize + d] = (bytes[s + 2].toInt() and 0xFF) / 255f
                x++
            }
            y++
        }
        return FloatBuffer.wrap(floats, 0, 3 * planeSize) to geometry
    }

    /**
     * Runs the declared semantic variant. Output tensors are read as contiguous buffers and the
     * whole [OrtSession.Result] (and with it every output tensor) is closed before decoding, so a
     * failing frame cannot retain native memory. No YOLO box/NMS logic is applied here.
     */
    private fun detectSemanticTerrain(
        img: Mat,
        runtime: SemanticTerrainRuntime,
        confThresh: Float,
    ): Map<String, MutableList<FloatArray>> {
        val preprocessStart = diagnosticStart()
        val (buffer, geometry) = preprocessSemantic(img)
        recordStage(timingStages.preprocess, preprocessStart)
        val tensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1L, 3L, inputH.toLong(), inputW.toLong()))
        val timed = try {
            runSemanticInference(tensor)
        } finally {
            tensor.close()
        }
        // Decode span starts where the measured inference run ended: raster copy + decoder work.
        val decodeStart = timed.runEndedAtMs

        val contentPixels = geometry.contentWidth * geometry.contentHeight
        val croppedIds = ByteArray(contentPixels)
        val croppedConfidence = FloatArray(contentPixels)
        var row = 0
        while (row < geometry.contentHeight) {
            val source = (geometry.padY + row) * inputW + geometry.padX
            System.arraycopy(timed.classIds, source, croppedIds, row * geometry.contentWidth, geometry.contentWidth)
            System.arraycopy(timed.confidence, source, croppedConfidence, row * geometry.contentWidth, geometry.contentWidth)
            row++
        }

        val boxes = runtime.decoder.decode(
            manifest = runtime.manifest,
            classIds = croppedIds,
            confidence = croppedConfidence,
            contentWidth = geometry.contentWidth,
            contentHeight = geometry.contentHeight,
            originalWidth = img.cols(),
            originalHeight = img.rows(),
            tileSizePx = runtime.tileSize,
            confidenceThreshold = confThresh,
        )
        if (boxes.isEmpty()) {
            recordStage(timingStages.decode, decodeStart)
            return emptyMap()
        }
        val results = HashMap<String, MutableList<FloatArray>>()
        for (box in boxes) {
            results.getOrPut(box.name) { ArrayList() }
                .add(floatArrayOf(box.x1.toFloat(), box.y1.toFloat(), box.x2.toFloat(), box.y2.toFloat()))
        }
        recordStage(timingStages.decode, decodeStart)
        return results
    }

    /**
     * Copied rasters plus the diagnostic timestamp at which the measured session.run returned.
     * [runEndedAtMs] is -1 when the diagnostic clock is unavailable; decode then goes unreported.
     */
    private class SemanticOutputs(
        val classIds: ByteArray,
        val confidence: FloatArray,
        val runEndedAtMs: Long,
    )

    private fun runSemanticInference(tensor: OnnxTensor): SemanticOutputs {
        val attemptStart = diagnosticStart()
        var attemptRecorded = false
        try {
            val result = session.run(mapOf(inputName to tensor))
            val runEndedAtMs = diagnosticStart()
            recordStage(timingStages.inference, attemptStart)
            attemptRecorded = true
            val rasters = readSemanticOutputs(result)
            return SemanticOutputs(rasters.first, rasters.second, runEndedAtMs)
        } catch (first: ai.onnxruntime.OrtException) {
            if (!attemptRecorded) recordStage(timingStages.inference, attemptStart)
            if (providerName == "CPU") throw first
            Log.w(TAG, "$providerName semantic inference failed for ${modelFile.name} (${first.message}); rebuilding with CPU")
            try { session.close() } catch (_: Throwable) {}
            val loaded = createCpuSession(modelFile, configuredThreadCount)
            session = loaded.first
            providerName = loaded.second
            inputName = session.inputNames.first()
            observe {
                PipelineTimingTelemetry.model(
                    telemetryRole, requestedProviderName, providerName, configuredThreadCount,
                )
            }
            // Declared variant stays declared: execution provider may fall back, decoding may not.
            val manifest = semanticRuntime?.manifest
                ?: throw IllegalStateException("semantic provider fallback without a declared manifest")
            validateSemanticSession(manifest)
            val retryStart = diagnosticStart()
            val result = session.run(mapOf(inputName to tensor))
            val runEndedAtMs = diagnosticStart()
            recordStage(timingStages.inference, retryStart)
            val rasters = readSemanticOutputs(result)
            return SemanticOutputs(rasters.first, rasters.second, runEndedAtMs)
        }
    }

    /** Copies class/confidence rasters out of the ORT result; never returns a live tensor. */
    private fun readSemanticOutputs(result: OrtSession.Result): Pair<ByteArray, FloatArray> {
        try {
            val idsValue = result.get(SemanticTerrainRuntime.CLASS_IDS).orElse(null)
                ?: throw IllegalStateException("semantic model did not produce '${SemanticTerrainRuntime.CLASS_IDS}'")
            val confidenceValue = result.get(SemanticTerrainRuntime.CONFIDENCE).orElse(null)
                ?: throw IllegalStateException("semantic model did not produce '${SemanticTerrainRuntime.CONFIDENCE}'")
            val idsTensor = idsValue as? OnnxTensor
                ?: throw IllegalStateException("semantic 'class_ids' output is not a tensor")
            val confidenceTensor = confidenceValue as? OnnxTensor
                ?: throw IllegalStateException("semantic 'confidence' output is not a tensor")
            val pixels = inputW * inputH
            val ids = ByteArray(pixels)
            val confidence = FloatArray(pixels)
            val idsBuffer = idsTensor.byteBuffer
            if (idsBuffer.remaining() != pixels) {
                throw IllegalStateException("semantic class_ids has ${idsBuffer.remaining()} values, expected $pixels")
            }
            idsBuffer.get(ids)
            val confidenceBuffer = confidenceTensor.floatBuffer
            if (confidenceBuffer.remaining() != pixels) {
                throw IllegalStateException("semantic confidence has ${confidenceBuffer.remaining()} values, expected $pixels")
            }
            confidenceBuffer.get(confidence)
            return ids to confidence
        } finally {
            try { result.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Validates the declared variant against the real session IO. Any mismatch is fatal for this
     * model: no shape guessing, no output reordering, no silent legacy fallback.
     */
    private fun validateSemanticSession(manifest: SemanticTerrainManifest) {
        val expectedInput = longArrayOf(1L, 3L, manifest.height.toLong(), manifest.width.toLong())
        val inputs = session.inputInfo
        if (inputs.keys != setOf("images")) {
            throw IllegalStateException("semantic model must have exactly one input, found ${inputs.size}")
        }
        val inputInfo = inputs.values.first().info as? TensorInfo
            ?: throw IllegalStateException("semantic model input is not a tensor")
        if (inputInfo.type != OnnxJavaType.FLOAT || !inputInfo.shape.contentEquals(expectedInput)) {
            throw IllegalStateException(
                "semantic model input ${inputInfo.shape.contentToString()} does not match manifest " +
                    "${manifest.width}x${manifest.height}",
            )
        }
        val outputs = session.outputInfo
        if (outputs.keys != setOf(SemanticTerrainRuntime.CLASS_IDS, SemanticTerrainRuntime.CONFIDENCE)) {
            throw IllegalStateException("semantic model outputs ${outputs.keys} are not exactly class_ids+confidence")
        }
        val expectedRaster = longArrayOf(1L, manifest.height.toLong(), manifest.width.toLong())
        val idsInfo = outputs.getValue(SemanticTerrainRuntime.CLASS_IDS).info as? TensorInfo
            ?: throw IllegalStateException("semantic class_ids output is not a tensor")
        if (idsInfo.type != OnnxJavaType.UINT8 || !idsInfo.shape.contentEquals(expectedRaster)) {
            throw IllegalStateException("semantic class_ids must be uint8 [1,${manifest.height},${manifest.width}], found ${idsInfo.type} ${idsInfo.shape.contentToString()}")
        }
        val confidenceInfo = outputs.getValue(SemanticTerrainRuntime.CONFIDENCE).info as? TensorInfo
            ?: throw IllegalStateException("semantic confidence output is not a tensor")
        if (confidenceInfo.type != OnnxJavaType.FLOAT || !confidenceInfo.shape.contentEquals(expectedRaster)) {
            throw IllegalStateException("semantic confidence must be float32 [1,${manifest.height},${manifest.width}], found ${confidenceInfo.type} ${confidenceInfo.shape.contentToString()}")
        }
    }

    /**
     * The sidecar is `<resolved model basename>.json` in the model's own directory, matching
     * upstream `os.path.splitext(model_path)[0] + '.json'`. A declared semantic build without a
     * readable manifest is an error, not a reason to run the legacy decoder.
     */
    private fun loadSemanticManifest(resolvedModel: File): SemanticTerrainManifest {
        val sidecar = File(resolvedModel.parentFile, resolvedModel.nameWithoutExtension + ".json")
        if (!sidecar.isFile) {
            throw IllegalStateException(
                "semantic terrain declared but manifest is missing next to model: ${sidecar.absolutePath}",
            )
        }
        val text = try {
            sidecar.readText()
        } catch (t: Throwable) {
            throw IllegalStateException("semantic terrain manifest is unreadable: ${sidecar.name}", t)
        }
        return try {
            SemanticTerrainManifest.parse(text)
        } catch (t: IllegalArgumentException) {
            throw IllegalStateException("semantic terrain manifest is invalid: ${sidecar.name}: ${t.message}", t)
        }
    }

    fun detectObjects(img: Mat, confThresh: Float = 0.6f): Map<String, MutableList<FloatArray>> {
        semanticRuntime?.let { return detectSemanticTerrain(img, it, confThresh) }
        val preprocessStart = diagnosticStart()
        val (buf, nw, nh) = preprocess(img)
        recordStage(timingStages.preprocess, preprocessStart)
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1L, 3L, inputH.toLong(), inputW.toLong()))
        val timed = try {
            runInference(tensor)
        } finally {
            tensor.close()
        }
        val prediction = timed.prediction
        if (prediction == null) return emptyMap()
        // Decode span starts where the measured inference run ended: output materialization + NMS.
        val decodeStart = timed.runEndedAtMs

        val nDet = prediction.shape[0]; val nCh = prediction.shape[1]
        val nClasses = nCh - 4
        if (nClasses <= 0) {
            recordStage(timingStages.decode, decodeStart)
            return emptyMap()
        }

        val boxes = ArrayList<FloatArray>(); val scores = ArrayList<Float>(); val classIds = ArrayList<Int>()
        for (i in 0 until nDet) {
            var best = 0f; var bestCls = 0
            for (c in 0 until nClasses) {
                val v = prediction.get(i, 4 + c)
                if (v > best) { best = v; bestCls = c }
            }
            if (best < confThresh) continue
            val cx = prediction.get(i, 0)
            val cy = prediction.get(i, 1)
            val bw = prediction.get(i, 2)
            val bh = prediction.get(i, 3)
            // YOLO emits cx/cy/width/height. NMS, like the PC implementation, requires xyxy.
            boxes.add(floatArrayOf(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f))
            scores.add(best); classIds.add(bestCls)
        }

        val origH = img.rows(); val origW = img.cols()
        val scaleW = origW.toDouble() / nw; val scaleH = origH.toDouble() / nh

        val results = HashMap<String, MutableList<FloatArray>>()
        val clsGroups = HashMap<Int, MutableList<Int>>()
        for (k in boxes.indices) clsGroups.getOrPut(classIds[k]) { ArrayList() }.add(k)

        for ((cls, idxs) in clsGroups) {
            val keep = nms(idxs.map { boxes[it] }, idxs.map { scores[it] }, 0.6f)
            val clsName = classes?.getOrNull(cls) ?: cls.toString()
            val list = results.getOrPut(clsName) { ArrayList() }
            for (k in keep) {
                val origIdx = idxs[k]; val b = boxes[origIdx]
                list.add(floatArrayOf(
                    (b[0] * scaleW).toFloat(),
                    (b[1] * scaleH).toFloat(),
                    (b[2] * scaleW).toFloat(),
                    (b[3] * scaleH).toFloat(),
                ))
            }
        }
        recordStage(timingStages.decode, decodeStart)
        return results
    }

    /** Prediction plus the diagnostic timestamp at which the measured session.run returned. */
    private class TimedPrediction(val prediction: SimpleMatrix?, val runEndedAtMs: Long)

    private fun runInference(tensor: OnnxTensor): TimedPrediction {
        val attemptStart = diagnosticStart()
        var attemptRecorded = false
        try {
            val outputs = session.run(mapOf(inputName to tensor))
            val runEndedAtMs = diagnosticStart()
            recordStage(timingStages.inference, attemptStart)
            attemptRecorded = true
            return try {
                TimedPrediction(normalizeOutput(outputs[0].value), runEndedAtMs)
            } finally { outputs.close() }
        } catch (first: Throwable) {
            if (!attemptRecorded) recordStage(timingStages.inference, attemptStart)
            if (providerName == "CPU") throw first
            val previousProvider = providerName
            Log.w(TAG, "$providerName inference failed for ${modelFile.name} (${first.message}); rebuilding with CPU")
            try { session.close() } catch (_: Throwable) {}
            val loaded = createCpuSession(modelFile, configuredThreadCount)
            session = loaded.first
            providerName = loaded.second
            inputName = session.inputNames.first()
            PerceptionPerformanceTelemetry.recordFallback(
                telemetryRole, previousProvider, providerName, configuredThreadCount,
            )
            observe {
                PipelineTimingTelemetry.model(
                    telemetryRole, requestedProviderName, providerName, configuredThreadCount,
                )
            }
            val retryStart = diagnosticStart()
            val outputs = session.run(mapOf(inputName to tensor))
            val runEndedAtMs = diagnosticStart()
            recordStage(timingStages.inference, retryStart)
            return try {
                TimedPrediction(normalizeOutput(outputs[0].value), runEndedAtMs)
            } finally { outputs.close() }
        }
    }

    private fun nms(boxes: List<FloatArray>, scores: List<Float>, iou: Float): List<Int> {
        val order = boxes.indices.sortedByDescending { scores[it] }
        val keep = ArrayList<Int>(); val suppressed = BooleanArray(boxes.size)
        for (orderIndex in order.indices) {
            val i = order[orderIndex]
            if (suppressed[i]) continue
            keep.add(i)
            val a = boxes[i]; val aArea = max(0f, a[2] - a[0]) * max(0f, a[3] - a[1])
            for (nextIndex in orderIndex + 1 until order.size) {
                val j = order[nextIndex]
                if (suppressed[j]) continue
                val b = boxes[j]; val bArea = max(0f, b[2] - b[0]) * max(0f, b[3] - b[1])
                val xx1 = max(a[0], b[0]); val yy1 = max(a[1], b[1])
                val xx2 = minOf(a[2], b[2]); val yy2 = minOf(a[3], b[3])
                val overlapW = max(0f, xx2 - xx1); val overlapH = max(0f, yy2 - yy1)
                val inter = overlapW * overlapH; val union = aArea + bArea - inter
                if (union > 0f && inter / union > iou) suppressed[j] = true
            }
        }
        return keep
    }


    private fun normalizeOutput(raw: Any?): SimpleMatrix? {
        if (raw == null) return null
        var value: Any? = raw
        if (value is java.util.List<*>) {
            if (value.isEmpty()) return null
            value = value[0]
        }

        return when (value) {
            is FloatArray -> SimpleMatrix(value, intArrayOf(1, value.size))
            is Array<*> -> flattenTo2D(value)
            else -> null
        }
    }

    private fun flattenTo2D(arr: Array<*>): SimpleMatrix? {
        if (arr.isEmpty()) return SimpleMatrix(FloatArray(0), intArrayOf(0, 0))
        val first = arr[0]
        return when (first) {
            is FloatArray -> {
                val rows = arr.size; val cols = first.size
                val out = FloatArray(rows * cols)
                for (i in 0 until rows) System.arraycopy(arr[i] as FloatArray, 0, out, i * cols, cols)
                if (rows < cols && rows <= 256) {
                    val t = FloatArray(rows * cols)
                    for (i in 0 until rows) for (j in 0 until cols) t[j * rows + i] = out[i * cols + j]
                    SimpleMatrix(t, intArrayOf(cols, rows))
                } else {
                    SimpleMatrix(out, intArrayOf(rows, cols))
                }
            }
            is Array<*> -> flattenTo2D(first as Array<*>)
            else -> null
        }
    }

    class SimpleMatrix(val data: FloatArray, val shape: IntArray) {
        fun get(row: Int, col: Int): Float = data[row * shape[1] + col]
    }

    fun close() {
        try { session.close() } catch (t: Throwable) {
            Log.w(TAG, "Failed to close ${modelFile.name}: ${t.message}")
        }
    }

    /** Immutable declared-variant state: manifest + reusable, bounded decode scratch. */
    private class SemanticTerrainRuntime(
        val manifest: SemanticTerrainManifest,
        val tileSize: Double,
    ) {
        val decoder = SemanticTerrainDecoder()

        companion object {
            const val CLASS_IDS = "class_ids"
            const val CONFIDENCE = "confidence"
        }
    }

    private class SemanticPreprocessScratch {
        private var byteBuffer = ByteArray(0)
        private var floatBuffer = FloatArray(0)

        fun bytes(required: Int): ByteArray {
            if (byteBuffer.size < required) byteBuffer = ByteArray(required)
            return byteBuffer
        }

        fun floats(required: Int): FloatArray {
            if (floatBuffer.size < required) floatBuffer = FloatArray(required)
            return floatBuffer
        }
    }

    private class PreprocessScratch {
        private var byteBuffer = ByteArray(0)
        private var floatBuffer = FloatArray(0)

        fun bytes(required: Int): ByteArray {
            if (byteBuffer.size < required) byteBuffer = ByteArray(required)
            return byteBuffer
        }

        fun floats(required: Int): FloatArray {
            if (floatBuffer.size < required) floatBuffer = FloatArray(required)
            return floatBuffer
        }
    }

    companion object {
        private const val TAG = "PylaDetect"
        private val preprocessScratch = ThreadLocal.withInitial { PreprocessScratch() }
        private val semanticScratch = ThreadLocal.withInitial { SemanticPreprocessScratch() }

        private val emulator: Boolean by lazy {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: ""
            if (abi.startsWith("x86")) return@lazy true
            val fp = (android.os.Build.FINGERPRINT ?: "").lowercase()
            val model = (android.os.Build.MODEL ?: "").lowercase()
            val hw = (android.os.Build.HARDWARE ?: "").lowercase()
            val product = (android.os.Build.PRODUCT ?: "").lowercase()
            val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase()
            fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
                model.contains("emulator") || model.contains("android sdk") ||
                hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox") ||
                hw.contains("ttvm") || hw.contains("nox") || hw.contains("mumu") ||
                product.contains("mumu") || product.contains("emulator") || product.contains("sdk") ||
                manufacturer.contains("genymotion")
        }

        fun isEmulator(): Boolean = emulator

        fun configuredThreads(): Int {
            val configured = try {
                PylaConfig.load("cfg/general_config.toml").getString("used_threads", "auto").trim()
            } catch (_: Throwable) {
                "auto"
            }
            return configured.toIntOrNull()
                ?.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                ?: optimalThreads()
        }

        fun optimalThreads(maxLimit: Int = 4): Int =
            DeviceProfile.autoInferenceThreads.coerceAtMost(maxLimit).coerceAtLeast(1)
    }
}