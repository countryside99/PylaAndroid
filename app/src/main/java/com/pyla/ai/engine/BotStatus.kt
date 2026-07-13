package com.pyla.ai.engine

import android.util.Log

object BotStatus {
    @Volatile var engineRunning: Boolean = false
    @Volatile var captureSize: String = ""
    @Volatile var currentState: String = ""
    @Volatile var lastAction: String = ""
    @Volatile var inputConnected: Boolean = false
    @Volatile var frameCount: Int = 0
    @Volatile var lastError: String = ""
    @Volatile var queueSummary: String = ""

    fun action(msg: String) {
        lastAction = msg
        PylaLog.p("PylaStatus", msg)
    }

    fun error(msg: String) {
        lastError = msg
        PylaLog.e("PylaStatus", msg)
    }
}