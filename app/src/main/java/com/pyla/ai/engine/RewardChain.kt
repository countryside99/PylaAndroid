package com.pyla.ai.engine

/**
 * Decides whether an unrecognised screen inside a reward sequence should be tapped onward.
 *
 * A drop can multiply (Chaos Drop): the game then shows a run of animated screens that carry no
 * heading and no plain background, each needing one more tap, sometimes many in a row. A user
 * report showed the bot recognising the drop, pressing it, and then waiting 50 s, 8 s and 20 s on
 * such screens until someone tapped by hand.
 *
 * Tapping an unrecognised screen is only acceptable under narrow conditions, all required:
 *  - a reward screen was positively recognised at most [PENDING_MS] ago,
 *  - no other screen was positively recognised since (lobby, match, result, dialog end the chain),
 *  - the screen has stayed unrecognised for [SETTLE_MS], so a normal transition is not tapped.
 */
object RewardChain {
    const val PENDING_MS = 45_000L
    const val SETTLE_MS = 1_500L

    fun pending(nowMs: Long, lastRewardAtMs: Long, unknownSinceMs: Long): Boolean {
        if (lastRewardAtMs <= 0L || unknownSinceMs <= 0L) return false
        if (nowMs < lastRewardAtMs || nowMs < unknownSinceMs) return false
        return nowMs - lastRewardAtMs <= PENDING_MS && nowMs - unknownSinceMs >= SETTLE_MS
    }
}
