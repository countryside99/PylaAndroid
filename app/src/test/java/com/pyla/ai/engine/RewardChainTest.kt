package com.pyla.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardChainTest {
    @Test fun unrecognisedScreenAfterARewardIsTappedOnceItHasSettled() {
        assertFalse(RewardChain.pending(nowMs = 11_000, lastRewardAtMs = 10_000, unknownSinceMs = 10_500))
        assertTrue(RewardChain.pending(nowMs = 12_000, lastRewardAtMs = 10_000, unknownSinceMs = 10_500))
        assertTrue(RewardChain.pending(nowMs = 55_000, lastRewardAtMs = 10_000, unknownSinceMs = 10_500))
    }

    @Test fun chainExpiresAndNeverStartsWithoutAReward() {
        assertFalse(RewardChain.pending(nowMs = 55_001, lastRewardAtMs = 10_000, unknownSinceMs = 10_500))
        assertFalse(RewardChain.pending(nowMs = 12_000, lastRewardAtMs = 0, unknownSinceMs = 10_500))
        assertFalse(RewardChain.pending(nowMs = 12_000, lastRewardAtMs = 10_000, unknownSinceMs = 0))
        assertFalse(RewardChain.pending(nowMs = 9_000, lastRewardAtMs = 10_000, unknownSinceMs = 8_000))
    }
}
