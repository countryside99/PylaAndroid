package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class DefeatedExitGateTest {
    private fun obs(at: Long, enabled: Boolean = true, x: Float = 1300f, y: Float = 650f, w: Int = 1560, h: Int = 720) =
        DefeatedExitGate.Observation(at, w, h, x, y, enabled)
    private fun admitted(g: DefeatedExitGate): DefeatedExitGate.Ticket {
        assertNull(g.observe(obs(100), g.generation(), 100))
        return requireNotNull(g.observe(obs(200), g.generation(), 200))
    }
    @Test fun enabledDistinctStablePairAdmits() {
        val g = DefeatedExitGate(); val t = admitted(g)
        assertTrue(g.allows(t, 200)); assertEquals(1300f, t.observation.x, 0f)
    }
    @Test fun resultScreenGateKeepsEvidenceOnlyOnItsOwnScreen() {
        val g = DefeatedExitGate(screen = "end_ranked")
        assertNull(g.observe(obs(100), g.generation(), 100))
        g.screenChanged("end_ranked") // own screen: evidence survives
        val t = requireNotNull(g.observe(obs(200), g.generation(), 200))
        assertTrue(g.allows(t, 200))
        g.screenChanged("defeated") // any other screen revokes it
        assertFalse(g.allows(t, 200))
    }
    @Test fun oneObservationOrRepeatedCaptureNeverAdmits() {
        val g = DefeatedExitGate()
        repeat(5) { assertNull(g.observe(obs(100), g.generation(), 100)) }
    }
    @Test fun missingOrDisabledControlRevokesAndNeedsNewPair() {
        val g = DefeatedExitGate(); val t = admitted(g)
        assertNull(g.observe(obs(300, false), g.generation(), 300)); assertFalse(g.allows(t, 300))
        assertNull(g.observe(obs(400), g.generation(), 400))
    }
    @Test fun pauseAndResumeWithoutQueueSamplingNeverRevivesOldTicket() {
        val g = DefeatedExitGate(); val t = admitted(g); val old = g.generation()
        g.invalidate(210); g.invalidate(220)
        assertFalse(g.allows(t, 230)); assertNull(g.observe(obs(240), old, 240))
        assertNull(g.observe(obs(220), g.generation(), 250))
        assertNull(g.observe(obs(260), g.generation(), 260))
        assertNotNull(g.observe(obs(270), g.generation(), 270))
        assertFalse(g.allows(t, 270))
    }
    @Test fun staleInFlightGenerationCannotRevokeNewTicket() {
        val g = DefeatedExitGate(); val old = g.generation(); g.invalidate(10)
        val t = admitted(g)
        assertNull(g.observe(obs(250, false), old, 250)); assertTrue(g.allows(t, 250))
    }
    @Test fun bothMembersMustRemainFreshAtDispatch() {
        val g = DefeatedExitGate(); val t = admitted(g)
        assertTrue(g.allows(t, 3100)); assertFalse(g.allows(t, 3101))
    }
    @Test fun expiredFirstMemberCannotIssueTicket() {
        val g = DefeatedExitGate()
        assertNull(g.observe(obs(100), g.generation(), 100))
        assertNull(g.observe(obs(3100), g.generation(), 3101))
    }
    @Test fun invalidFutureAndNonfiniteEvidenceDenies() {
        val invalid = listOf(obs(0), obs(-1), obs(501), obs(100, x = Float.NaN),
            obs(100, y = Float.POSITIVE_INFINITY), obs(100, x = -1f), obs(100, x = 1560f),
            obs(100, y = 720f), obs(100, w = 0), obs(100, h = -1))
        for (o in invalid) {
            val g = DefeatedExitGate(); val t = admitted(g)
            assertNull(g.observe(o, g.generation(), 500)); assertFalse(g.allows(t, 500))
        }
    }
    @Test fun geometryAndMovingControlsNeedNewStablePair() {
        for (changed in listOf(obs(200, x = 1310f), obs(200, y = 660f), obs(200, w = 1600))) {
            val g = DefeatedExitGate()
            assertNull(g.observe(obs(100), g.generation(), 100))
            assertNull(g.observe(changed, g.generation(), 200))
            assertNotNull(g.observe(changed.copy(capturedAtMs = 300), g.generation(), 300))
        }
    }
    @Test fun geometryRoundTripCannotJoinOldAndNewCapture() {
        val g = DefeatedExitGate()
        assertNull(g.observe(obs(100).copy(geometryGeneration = 1), g.generation(), 100))
        assertNull(g.observe(obs(200).copy(geometryGeneration = 3), g.generation(), 200))
        assertNotNull(g.observe(obs(300).copy(geometryGeneration = 3), g.generation(), 300))
    }
    @Test fun unknownRevokesQueuedExit() {
        val g = DefeatedExitGate(); val t = admitted(g)
        g.screenChanged("unknown"); assertFalse(g.allows(t, 210))
        assertNull(g.observe(obs(220), g.generation(), 220))
    }
    @Test fun duplicateDispatchIsSuppressedUntilFreshPostAttemptPairAndInterval() {
        val g = DefeatedExitGate(); val t = admitted(g)
        assertNull(g.observe(obs(300), g.generation(), 300))
        assertNull(g.observe(obs(1199), g.generation(), 1199))
        val retry = requireNotNull(g.observe(obs(1200), g.generation(), 1200))
        assertFalse(g.allows(t, 1200)); assertTrue(g.allows(retry, 1200))
    }
    @Test fun retryNeedsBothCapturesAfterPreviousAttempt() {
        val g = DefeatedExitGate(); admitted(g)
        assertNull(g.observe(obs(1200), g.generation(), 1200))
        assertNotNull(g.observe(obs(1201), g.generation(), 1201))
    }
    @Test fun boundedRetriesAreNotResetByUnknownOrMissingExit() {
        val g = DefeatedExitGate(); admitted(g)
        for (at in listOf(1300L, 2500L)) {
            g.screenChanged("unknown")
            assertNull(g.observe(obs(at), g.generation(), at))
            assertNotNull(g.observe(obs(at + 1), g.generation(), at + 1))
        }
        g.screenChanged("unknown")
        assertNull(g.observe(obs(4000), g.generation(), 4000))
        assertNull(g.observe(obs(4001), g.generation(), 4001))
    }
    @Test fun newMatchOrLobbyAllowsNextDefeatWithoutRevivingOldTicket() {
        for (state in listOf("lobby", "match")) {
            val g = DefeatedExitGate(maxAttempts = 1); val old = admitted(g)
            g.screenChanged(state)
            assertNull(g.observe(obs(1300), g.generation(), 1300))
            assertNotNull(g.observe(obs(1301), g.generation(), 1301)); assertFalse(g.allows(old, 1301))
        }
    }
}
