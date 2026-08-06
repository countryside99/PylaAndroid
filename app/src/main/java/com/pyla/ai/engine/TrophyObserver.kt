package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig

enum class GameMode { CLASSIC, TRIO_SHOWDOWN }
enum class MatchResultKind { VICTORY, DRAW, DEFEAT }

data class ParsedGameResult(
    val gamemode: GameMode,
    val result: MatchResultKind,
    val place: Int? = null,
    val rawString: String = "",
)

class TrophyObserver(
    initialTrophies: Int = 0,
    initialWins: Int = 0,
    initialWinStreak: Int = 0,
) {
    var currentTrophies: Int = initialTrophies
    var currentWins: Int = initialWins
    var winStreak: Int = initialWinStreak
    var matchCounter = 0

    private val trophiesMultiplier: Int = PylaConfig.load("cfg/general_config.toml").getInt("trophies_multiplier", 1)

    private val trophyLoseRanges: List<Pair<Int, Int>> = listOf(
        49 to 0, 299 to 1, 599 to 2, 799 to 3, 999 to 4, 1099 to 5, 1199 to 6, 1299 to 7,
        1499 to 8, 1799 to 9, 3999 to 10, Int.MAX_VALUE to 15,
    )
    private val trophyWinRanges: List<Pair<Int, Int>> = listOf(
        1999 to 10, 2499 to 8, 2799 to 6, 2999 to 4, 3099 to 2, Int.MAX_VALUE to 1,
    )
    private val showdownTrioRanges: List<Pair<Int, IntArray>> = listOf(
        49 to intArrayOf(11, 5, 5, 5),
        99 to intArrayOf(11, 5, 4, -1),
        199 to intArrayOf(11, 5, 3, -1),
        299 to intArrayOf(11, 5, 2, -1),
        499 to intArrayOf(11, 5, 2, -2),
        599 to intArrayOf(11, 5, 1, -2),
        799 to intArrayOf(11, 5, 1, -3),
        999 to intArrayOf(11, 5, 1, -4),
        1099 to intArrayOf(11, 5, 0, -6),
        1199 to intArrayOf(11, 5, 0, -7),
        1299 to intArrayOf(11, 5, 0, -8),
        1499 to intArrayOf(11, 5, 0, -9),
        1799 to intArrayOf(11, 5, -5, -10),
        1999 to intArrayOf(11, 5, -5, -11),
        2199 to intArrayOf(9, 4, -5, -11),
        Int.MAX_VALUE to intArrayOf(9, 4, -5, -11),
    )

    fun winStreakGain(): Int = if (currentTrophies < 2000) minOf(winStreak - 1, 10) else 0

    fun calcLostDecrement(): Int {
        for ((maxT, loss) in trophyLoseRanges) if (currentTrophies <= maxT) return loss
        return 0
    }
    fun calcWinIncrement(): Int {
        for ((maxT, gain) in trophyWinRanges) if (currentTrophies <= maxT) return gain * trophiesMultiplier + winStreakGain()
        return 0
    }
    fun calcShowdownDelta(place: Int): Int {
        for ((maxT, deltas) in showdownTrioRanges) {
            if (currentTrophies <= maxT) {
                return deltas[place] * trophiesMultiplier + (if (place < 2) winStreakGain() else 0)
            }
        }
        return 0
    }

    fun parseGameResult(rawResult: String): ParsedGameResult {
        Log.i(TAG, "Found game result: $rawResult")
        return if ("showdown" in rawResult) {
            val place = rawResult.split("_").last().toIntOrNull() ?: 0
            val gm = if ("trio_showdown" in rawResult) GameMode.TRIO_SHOWDOWN else GameMode.CLASSIC
            val result = when {
                place < 2 -> MatchResultKind.VICTORY
                place == 2 -> MatchResultKind.DRAW
                else -> MatchResultKind.DEFEAT
            }
            ParsedGameResult(gm, result, place, rawResult)
        } else {
            val result = when (rawResult) {
                "victory" -> MatchResultKind.VICTORY
                "draw" -> MatchResultKind.DRAW
                else -> MatchResultKind.DEFEAT
            }
            ParsedGameResult(GameMode.CLASSIC, result, null, rawResult)
        }
    }

    fun addTrophies(parsed: ParsedGameResult, currentBrawler: String, powerLevel: Int? = null) {
        val old = currentTrophies; val oldStreak = winStreak
        var delta = 0
        when (parsed.result) {
            MatchResultKind.VICTORY -> {
                winStreak += 1
                delta = if (parsed.place != null) calcShowdownDelta(parsed.place) else calcWinIncrement()
            }
            MatchResultKind.DEFEAT -> {
                winStreak = 0
                delta = if (parsed.place != null) calcShowdownDelta(parsed.place) else -calcLostDecrement()
            }
            MatchResultKind.DRAW -> {
                delta = if (parsed.place != null) calcShowdownDelta(parsed.place) else 0
            }
        }
        if (currentTrophies >= 1000 && currentTrophies + delta < 1000) currentTrophies = 1000
        else if (currentTrophies >= 2000 && currentTrophies + delta < 2000) currentTrophies = 2000
        else currentTrophies += delta

        Log.i(TAG, "Trophies: $old -> $currentTrophies  streak: $oldStreak -> $winStreak  delta=$delta")
        matchCounter += 1
    }

    fun addWin(parsed: ParsedGameResult) {
        if (parsed.result == MatchResultKind.VICTORY) currentWins += 1
        Log.i(TAG, "Current wins: $currentWins")
    }

    fun changeTrophies(newTrophies: Int) {
        Log.i(TAG, "Trophies changed from $currentTrophies to $newTrophies")
        currentTrophies = newTrophies
    }

    companion object { private const val TAG = "PylaTrophy" }
}