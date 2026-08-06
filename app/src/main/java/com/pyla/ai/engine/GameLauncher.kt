package com.pyla.ai.engine

import android.content.Context
import android.content.Intent
import com.pyla.ai.config.PylaConfig

object GameLauncher {

    private const val TAG = "PylaLauncher"
    const val DEFAULT_PACKAGE = "com.supercell.brawlstars"

    fun gamePackage(): String = try {
        PylaConfig.load("cfg/general_config.toml").getString("brawl_stars_package", DEFAULT_PACKAGE)
            .ifBlank { DEFAULT_PACKAGE }
    } catch (t: Throwable) {
        DEFAULT_PACKAGE
    }

    fun isInstalled(context: Context, pkg: String = gamePackage()): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null


    fun launch(context: Context, pkg: String = gamePackage()): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            PylaLog.w(TAG, "Brawl Stars ($pkg) is not installed on this device")
            BotStatus.error("Brawl Stars not installed ($pkg)")
            return false
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            PylaLog.p(TAG, "Launched $pkg")
            BotStatus.action("Launched Brawl Stars")
            true
        } catch (t: Throwable) {
            PylaLog.e(TAG, "Failed to launch $pkg", t)
            false
        }
    }
}
