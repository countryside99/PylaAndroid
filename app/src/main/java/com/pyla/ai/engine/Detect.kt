package com.pyla.ai.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.pyla.ai.config.PylaConfig
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
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val preferredDevice: String = PylaConfig.load("cfg/general_config.toml")
        .getString("cpu_or_gpu", "auto").trim().lowercase()
    private val modelFile: File
    private var session: OrtSession
    private var providerName: String
    private var inputName: String
    private val inputH: Int = inputSize.first
    private val inputW: Int = inputSize.second
    private val padValue: Float = 128f / 255f
    private val configuredThreadCount = threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    init {
        try { org.opencv.core.Core.setNumThreads(configuredThreadCount) } catch (_: Throwable) {}
        modelFile = resolveModel(modelPath)
        val loaded = createSessionWithFallback(modelFile, configuredThreadCount)
        session = loaded.first
        providerName = loaded.second
        inputName = session.inputNames.first()
        Log.i(TAG, "Loaded ${modelFile.name} provider=$providerName inputs=${session.inputNames} outputs=${session.outputNames}")
    }

    private fun baseOptions(threads: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Models are executed sequentially. Multiple inter-op pools only oversubscribe low-end CPUs.
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads)
    }

    private fun createSessionWithFallback(f: File, threads: Int): Pair<OrtSession, String> {
        if (preferredDevice != "cpu" && !isEmulator()) {
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

    fun detectObjects(img: Mat, confThresh: Float = 0.6f): Map<String, MutableList<FloatArray>> {
        val (buf, nw, nh) = preprocess(img)
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1L, 3L, inputH.toLong(), inputW.toLong()))
        val prediction = try {
            runInference(tensor)
        } finally {
            tensor.close()
        }
        if (prediction == null) return emptyMap()

        val nDet = prediction.shape[0]; val nCh = prediction.shape[1]
        val nClasses = nCh - 4
        if (nClasses <= 0) return emptyMap()

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
        return results
    }

    private fun runInference(tensor: OnnxTensor): SimpleMatrix? {
        try {
            val outputs = session.run(mapOf(inputName to tensor))
            return try { normalizeOutput(outputs[0].value) } finally { outputs.close() }
        } catch (first: Throwable) {
            if (providerName == "CPU") throw first
            Log.w(TAG, "$providerName inference failed for ${modelFile.name} (${first.message}); rebuilding with CPU")
            try { session.close() } catch (_: Throwable) {}
            val loaded = createCpuSession(modelFile, configuredThreadCount)
            session = loaded.first
            providerName = loaded.second
            inputName = session.inputNames.first()
            val outputs = session.run(mapOf(inputName to tensor))
            return try { normalizeOutput(outputs[0].value) } finally { outputs.close() }
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