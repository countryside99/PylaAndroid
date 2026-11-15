package com.pyla.ai.diagnostics

import android.os.SystemClock

/**
 * Process-session, RAM-only pipeline timing observations for diagnostics.
 *
 * Observation only: no images, tensors, configuration, paths, device IDs, file
 * or network I/O, no persistent log, no callbacks and no threads. Every public
 * function is noexcept: a failing clock, sink or allocation can never change
 * caller control flow, and a missing clock simply yields {@code -1} /
 * no observation.
 *
 * [nowMs] is the only sanctioned timing start for callers. It is not an
 * authoritative clock: on failure it returns -1 and callers must keep their
 * existing control decisions.
 *
 * Summaries are emitted at most once per 5 s window, one bounded line per stage,
 * from the thread that crosses the window boundary. Quantiles cover the last
 * 128 retained samples per stage and say so (`sample=last128`).
 */
object PipelineTimingTelemetry {
    /** Fixed stage schema. Adding/removing a stage changes the report schema. */
    enum class Stage {
        ENTITY_PREPROCESS,
        ENTITY_INFERENCE,
        ENTITY_DECODE,
        TERRAIN_PREPROCESS,
        TERRAIN_INFERENCE,
        TERRAIN_DECODE,
        CLOSE_PREPROCESS,
        CLOSE_INFERENCE,
        CLOSE_DECODE,
        CONTROL_RECOGNITION,
        PLAYSTYLE,

        /** Time to hand an input request to the existing Accessibility submission path. */
        INPUT_SUBMISSION,

        /**
         * Observation-only callback interval: elapsed includes the requested gesture
         * duration plus Android scheduling, not inference or game-effect time.
         */
        INPUT_CALLBACK,
        FRAME_AGE_START,
        FRAME_AGE_DECISION,

        /**
         * `boundary=authority_check`: age observed when the existing guard is evaluated.
         * Admission, dispatch and renewal paths are mixed; not one specific check.
         */
        FRAME_AGE_GUARD,

        /**
         * `boundary=request_before_queue`: age at request hand-off, before the queue.
         * This is not Android's actual dispatch time.
         */
        FRAME_AGE_DISPATCH,
        MOVEMENT_REQUEST_GAP,
        LOOP_TOTAL,
    }

    private val accumulator = PipelineTimingAccumulator(
        clockMs = {
            val value = nowMs()
            if (value < 0L) throw IllegalStateException("clock unavailable") else value
        },
        sink = { kind, detail ->
            DiagnosticLog.event(kind, detail)
            // Mirror the bounded 5 s summaries to Logcat so a slow device can be diagnosed
            // without submitting a report. Numbers only; never throws into the pipeline.
            try { android.util.Log.i("PylaTiming", "$kind $detail") } catch (_: Throwable) { }
        },
    )

    /** Monotonic milliseconds, or -1 when the platform clock is unavailable. Never throws. */
    fun nowMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        -1L
    }

    fun record(stage: Stage, valueMs: Long) {
        try {
            accumulator.record(stage, valueMs)
        } catch (_: Throwable) {
        }
    }

    /**
     * Records the duration since a previously observed [nowMs] start and returns it,
     * or -1 when it is negative, starts in the future or overflows. Callers must not
     * record the same interval a second time.
     */
    fun elapsed(stage: Stage, startedAtMs: Long): Long = try {
        accumulator.elapsed(stage, startedAtMs)
    } catch (_: Throwable) {
        -1L
    }

    /** Original frame age for FRAME_AGE_* stages; the capture timestamp is never restamped. */
    fun age(stage: Stage, capturedAtMs: Long) {
        try {
            accumulator.age(stage, capturedAtMs)
        } catch (_: Throwable) {
        }
    }

    /** Requested/effective session providers. Hardware is always reported UNVERIFIED. */
    fun model(role: String, requested: String, effective: String, threads: Int) {
        try {
            accumulator.model(role, requested, effective, threads)
        } catch (_: Throwable) {
        }
    }

    /** Gap between observed accepted movement requests, not a physical hold. */
    fun movementRequest() {
        try {
            accumulator.movementRequest()
        } catch (_: Throwable) {
        }
    }

    /** Fixed interruption vocabulary; unknown reasons fold to OTHER. */
    fun interruption(reason: String) {
        try {
            accumulator.interruption(reason)
        } catch (_: Throwable) {
        }
    }

    /** Clears run timing state and any pending partial window; nothing crosses a run boundary. */
    fun reset() {
        try {
            accumulator.reset()
        } catch (_: Throwable) {
        }
    }

    /**
     * Emits the pending partial window once (recorded samples, bounded summary).
     * Engine stop only, after the original revocation/release handling; RAM-only,
     * no disk or network. [reset] then clears state so nothing leaks into a new run.
     */
    fun flush() {
        try {
            accumulator.flush()
        } catch (_: Throwable) {
        }
    }
}
