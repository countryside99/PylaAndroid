package com.pyla.ai.engine

/** Fixed vocabulary only: never carry OCR, frame content, device IDs or user text into status. */
enum class RuntimeState(val label: String) {
    UNKNOWN("Unknown"), LOBBY("Lobby"), BRAWLER_LIST("Brawler list"), BRAWLER_DETAIL("Brawler detail"),
    MATCH("Match"), MATCHMAKING("Matchmaking"), RESULT("Result"), REWARD("Reward"), SHOP("Shop"), POPUP("Popup"),
    MATCH_START("Match starting"), DEFEATED("Defeated"), TRANSITION("Transition");

    companion object {
        fun fromClassifier(value: String?): RuntimeState = when {
            value == "lobby" -> LOBBY
            value == "brawler_selection" -> BRAWLER_LIST
            value == "match_making" -> MATCHMAKING
            value == "match_start" -> MATCH_START
            value == "defeated" -> DEFEATED
            value == "transition" -> TRANSITION
            value == "result_preview" -> RESULT
            value == "shop" -> SHOP
            value == "popup" -> POPUP
            value?.startsWith("end_") == true -> RESULT
            value == "nano_noodles" || value == "trophy_reward" || value == "reward_continue" || value == "reward_reveal" || value == "prestige_milestone" ||
                value?.startsWith("star_drop_") == true -> REWARD
            // Absence of a menu match is not positive gameplay evidence.
            else -> UNKNOWN
        }
    }
}

enum class VerificationPhase(val label: String) {
    STOPPED("Stopped"), VERIFY_STATE("Verifying state"), LOADING("Waiting for game"),
    OPEN_MENU("Opening menu"), VERIFY_GRID("Verifying list"), SCAN_NAMES("Scanning names"),
    SCROLL_GRID("Scrolling list"), VERIFY_DETAIL("Verifying detail"), VERIFY_SELECTION("Checking selection"),
    VERIFIED("Verified"), TIMEOUT("Verification timed out"), AWAIT_PLAYER("Awaiting player"),
    AWAIT_TARGET("Awaiting target"), APPROACH("Approaching target"), RETREAT("Keeping distance"),
    FOLLOW("Following teammate"), HOLD("Holding range"), BLOCKED("Path blocked"), AVOID("Avoiding wall"),
    ROAM("Roaming"), STRAFE("Moving at range"), ESCAPE("Trying escape");
}

data class RuntimeVerification(
    val state: RuntimeState = RuntimeState.UNKNOWN,
    val phase: VerificationPhase = VerificationPhase.STOPPED,
    val verified: Boolean = false,
) {
    val stateLine: String get() = "State: ${if (verified) state.label else RuntimeState.UNKNOWN.label}"
    val phaseLine: String get() = "Check: ${phase.label}"
}

/** Admission requires two distinct, fresh observations; an unknown frame revokes admission. */
class StateObservationGate(private val maxAgeMs: Long = 3_000L) {
    private var candidate: String? = null
    private var firstAt: Long = 0L
    private var lastAt: Long = 0L
    private var admitted = false

    @Synchronized
    fun observe(state: String, capturedAtMs: Long, nowMs: Long): Boolean {
        if (RuntimeState.fromClassifier(state) == RuntimeState.UNKNOWN || capturedAtMs <= 0 || capturedAtMs > nowMs || nowMs - capturedAtMs > maxAgeMs) {
            reset(); return false
        }
        if (state != candidate || capturedAtMs < lastAt || nowMs - lastAt > maxAgeMs) {
            candidate = state; firstAt = capturedAtMs; lastAt = capturedAtMs; admitted = false
            return false
        }
        if (capturedAtMs > lastAt) {
            firstAt = lastAt // preceding DISTINCT capture; keep a rolling fresh pair
            lastAt = capturedAtMs
            admitted = true
        }
        return admitted && firstAt > 0L && nowMs - firstAt <= maxAgeMs
    }

    @Synchronized
    fun reset() { candidate = null; firstAt = 0L; lastAt = 0L; admitted = false }
}
