package com.pyla.ai.engine

import android.util.Log

object PylaLog {
    private const val ROOT_TAG = "Pyla"

    fun p(tag: String, msg: String) {
        Log.i(ROOT_TAG, "[$tag] $msg")
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(ROOT_TAG, "[$tag] $msg")
        Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(ROOT_TAG, "[$tag] $msg", t)
        Log.e(tag, msg, t)
    }
}