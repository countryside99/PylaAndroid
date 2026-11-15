package com.pyla.ai.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.pyla.ai.config.PylaConfig
import java.io.File
import java.nio.FloatBuffer

/**
 * Measures, on this device and while the game is running, which XNNPACK thread count and which
 * execution provider run a model fastest, and returns the winning session.
 *
 * The policy itself lives in [InferenceTuner]; this file only creates sessions and times them.
 * Every failure returns `null`, and the caller then uses its original fixed selection.
 */
internal object SessionTuning {
    private const val TAG = "PylaDetect"

    /**
     * One warm-up plus one measured run per candidate. Tuning happens while the game is loading,
     * so every extra run costs seconds on exactly the devices that need the tuning most.
     */
    private const val RUNS = 2

    class Tuned(val session: OrtSession, val provider: String, val threads: Int, val medianMs: Long)

    fun tune(env: OrtEnvironment, model: File, fill: Float, emulator: Boolean): Tuned? {
        if (!threadsAreAutomatic()) return null
        val kept = HashMap<Int, OrtSession>()
        return try {
            val choice = InferenceTuner.climb(
                InferenceTuner.threadCandidates(Runtime.getRuntime().availableProcessors(), limit = 3),
            ) { threads ->
                val created = xnnpack(env, model, threads) ?: return@climb null
                val ms = measure(env, created, fill)
                if (ms == null) close(created) else kept[threads] = created
                ms
            } ?: return null
            var chosen = kept.remove(choice.key) ?: return null
            var provider = "XNNPACK"
            var medianMs = choice.medianMs
            var nnapiMs: Long? = null
            if (!emulator) {
                val nnapi = nnapi(env, model, choice.key)
                if (nnapi != null) {
                    nnapiMs = measure(env, nnapi, fill)
                    if (InferenceTuner.preferAlternative(medianMs, nnapiMs)) {
                        close(chosen)
                        chosen = nnapi; provider = "NNAPI"; medianMs = nnapiMs!!
                    } else {
                        close(nnapi)
                    }
                }
            }
            Log.i(TAG, "Tuned ${model.name}: provider=$provider threads=${choice.key} median=${medianMs}ms " +
                "xnnpack=${choice.measured} nnapi=${nnapiMs ?: "n/a"}")
            Tuned(chosen, provider, choice.key, medianMs)
        } catch (t: Throwable) {
            Log.w(TAG, "Tuning failed for ${model.name} (${t.javaClass.simpleName}); using the fixed selection")
            null
        } finally {
            kept.values.forEach(::close)
        }
    }

    private fun close(session: OrtSession) { try { session.close() } catch (_: Throwable) { } }

    private fun threadsAreAutomatic(): Boolean = try {
        PylaConfig.load("cfg/general_config.toml").getString("used_threads", "auto").trim().toIntOrNull() == null
    } catch (_: Throwable) { true }

    private fun xnnpack(env: OrtEnvironment, model: File, threads: Int): OrtSession? {
        val opts = OrtSession.SessionOptions()
        return try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            opts.setInterOpNumThreads(1)
            opts.setIntraOpNumThreads(1)
            env.createSession(model.absolutePath, opts)
        } catch (_: Throwable) { null } finally { opts.close() }
    }

    private fun nnapi(env: OrtEnvironment, model: File, threads: Int): OrtSession? {
        val opts = OrtSession.SessionOptions()
        return try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setInterOpNumThreads(1)
            opts.setIntraOpNumThreads(threads)
            opts.addNnapi()
            env.createSession(model.absolutePath, opts)
        } catch (_: Throwable) { null } finally { opts.close() }
    }

    /** Steady-state wall time of one inference on a constant tensor; null when it cannot run. */
    private fun measure(env: OrtEnvironment, session: OrtSession, fill: Float): Long? = try {
        val name = session.inputNames.first()
        val shape = (session.inputInfo.getValue(name).info as TensorInfo).shape
        if (shape.any { it <= 0L }) null else {
            val data = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()) { fill }
            val samples = ArrayList<Long>(RUNS)
            repeat(RUNS) {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
                    val started = System.nanoTime()
                    session.run(mapOf(name to tensor)).close()
                    samples.add((System.nanoTime() - started) / 1_000_000L)
                }
            }
            InferenceTuner.steadyMedian(samples)
        }
    } catch (_: Throwable) { null }
}
