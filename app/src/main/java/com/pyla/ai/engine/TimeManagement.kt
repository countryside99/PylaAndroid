package com.pyla.ai.engine

import com.pyla.ai.config.PylaConfig

class TimeManagement {
    private val thresholds: Map<String, Double> = run {
        val t = PylaConfig.load("cfg/time_tresholds.toml")
        val out = HashMap<String, Double>()
        for (key in listOf("state_check", "no_detections", "idle", "gadget", "hypercharge", "super",
            "wall_detection", "no_detection_proceed", "check_if_brawl_stars_crashed")) {
            out[key] = t.getDouble(key, 0.0)
        }
        out
    }
    private val states = HashMap<String, Double>().apply {
        val now = nowSec()
        for (k in thresholds.keys) put(k, now)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000.0

    private fun check(checkType: String): Boolean {
        val now = nowSec()
        val last = states[checkType] ?: now
        val thr = thresholds[checkType] ?: 0.0
        if (now - last >= thr) {
            states[checkType] = now
            return true
        }
        return false
    }

    fun stateCheck() = check("state_check")
    fun noDetectionsCheck() = check("no_detections")
    fun idleCheck() = check("idle")

    fun getThresholdMs(key: String): Long = ((thresholds[key] ?: 0.0) * 1000).toLong()
}