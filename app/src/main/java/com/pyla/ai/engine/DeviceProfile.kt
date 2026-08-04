package com.pyla.ai.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.pyla.ai.PylaApp

/** Conservative runtime limits used only when a setting is configured as "auto". */
object DeviceProfile {
    val cpuCores: Int by lazy { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    val totalRamMb: Long by lazy {
        try {
            val manager = PylaApp.ctx().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        } catch (_: Throwable) {
            4_096L
        }
    }

    val isLowRamDevice: Boolean by lazy {
        try {
            val manager = PylaApp.ctx().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.isLowRamDevice
        } catch (_: Throwable) {
            false
        }
    }

    val isLowEnd: Boolean by lazy {
        isLowRamDevice || cpuCores <= 4 || totalRamMb <= 3_072L
    }

    val isMidRange: Boolean by lazy {
        !isLowEnd && (cpuCores <= 6 || totalRamMb <= 4_096L)
    }

    val maxCaptureHeight: Int by lazy {
        when {
            isLowEnd -> 540
            isMidRange -> 640
            else -> 720
        }
    }

    val autoMaxIps: Int by lazy {
        when {
            isLowEnd -> 10
            isMidRange -> 16
            else -> 24
        }
    }

    val autoInferenceThreads: Int by lazy {
        when {
            cpuCores <= 2 -> 1
            isLowEnd -> 2
            isMidRange -> minOf(3, cpuCores - 1)
            else -> minOf(4, maxOf(2, cpuCores / 2))
        }.coerceAtLeast(1)
    }

    fun thermallySafeIps(baseIps: Int, powerManager: PowerManager?): Int {
        var limit = baseIps.coerceAtLeast(1)
        if (powerManager?.isPowerSaveMode == true) limit = minOf(limit, 8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            limit = when {
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> minOf(limit, 4)
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> minOf(limit, 6)
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> minOf(limit, 10)
                else -> limit
            }
        }
        return limit
    }

    fun summary(): String =
        "cores=$cpuCores ram=${totalRamMb}MB lowRam=$isLowRamDevice " +
            "capture=${maxCaptureHeight}p autoIps=$autoMaxIps ortThreads=$autoInferenceThreads"
}
