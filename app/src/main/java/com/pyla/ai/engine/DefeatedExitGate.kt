package com.pyla.ai.engine

/**
 * Fresh visible-control authority for EXIT only; it never grants gameplay or presses PLAY.
 * [screen] is the positively classified screen whose EXIT this gate guards.
 */
class DefeatedExitGate(
    private val screen: String = "defeated",
    private val maxAgeMs: Long = 3_000L,
    private val retryIntervalMs: Long = 1_000L,
    private val maxAttempts: Int = 3,
) {
    data class Observation(
        val capturedAtMs: Long, val width: Int, val height: Int,
        val x: Float, val y: Float, val enabled: Boolean,
        val geometryGeneration: Long = 0L,
    )
    data class Ticket internal constructor(
        val generation: Long, val serial: Long,
        val firstCapturedAtMs: Long, val observation: Observation,
    )
    private var generation = 0L
    private var serial = 0L
    private var captureBarrier = 0L
    private var previous: Observation? = null
    private var active: Ticket? = null
    private var attempts = 0
    private var lastAttemptAt = 0L

    @Synchronized fun generation(): Long = generation

    /** Every pause/resume/start/stop invalidates even an UNSAMPLED queued action. */
    @Synchronized fun invalidate(nowMs: Long) {
        generation++
        captureBarrier = maxOf(captureBarrier, nowMs)
        previous = null; active = null; attempts = 0; lastAttemptAt = 0L
    }

    /** Unknown revokes evidence but does not create an unlimited retry budget. */
    @Synchronized fun screenChanged(state: String?) {
        if (state == screen) return
        previous = null; active = null
        if (state == "lobby" || state == "match") {
            attempts = 0; lastAttemptAt = 0L
        }
    }

    /** Caller has positively recognized defeated AND its enabled EXIT in these exact pixels. */
    @Synchronized fun observe(observation: Observation, expectedGeneration: Long, nowMs: Long): Ticket? {
        if (expectedGeneration != generation) return null
        if (!valid(observation, nowMs) || observation.capturedAtMs <= captureBarrier) {
            previous = null; active = null
            return null
        }
        val prior = previous
        previous = observation
        if (prior == null || !valid(prior, nowMs) ||
            observation.capturedAtMs <= prior.capturedAtMs ||
            observation.width != prior.width || observation.height != prior.height ||
            observation.geometryGeneration != prior.geometryGeneration ||
            kotlin.math.abs(observation.x - prior.x) > 6f ||
            kotlin.math.abs(observation.y - prior.y) > 6f) {
            active = null
            return null
        }
        if (attempts >= maxAttempts ||
            lastAttemptAt > 0L && (nowMs - lastAttemptAt < retryIntervalMs || prior.capturedAtMs <= lastAttemptAt)) return null
        attempts++
        lastAttemptAt = nowMs
        return Ticket(generation, ++serial, prior.capturedAtMs, observation).also { active = it }
    }

    /** Rechecked by the actual input queue, including restored pending touches. */
    @Synchronized fun allows(ticket: Ticket, nowMs: Long): Boolean =
        active == ticket && ticket.generation == generation &&
            ticket.firstCapturedAtMs > captureBarrier && fresh(ticket.firstCapturedAtMs, nowMs) &&
            valid(ticket.observation, nowMs)

    private fun valid(o: Observation, now: Long): Boolean =
        o.enabled && o.width > 0 && o.height > 0 && o.x.isFinite() && o.y.isFinite() &&
            o.x >= 0f && o.y >= 0f && o.x < o.width && o.y < o.height && fresh(o.capturedAtMs, now)

    private fun fresh(at: Long, now: Long): Boolean = at > 0L && at <= now && now - at <= maxAgeMs
}
