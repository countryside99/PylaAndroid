package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig
import org.json.JSONObject
import java.io.File

data class BrawlerInfo(
    val safeRange: Double,
    val attackRange: Double,
    val superType: String,
    val superRange: Double,
    val ignoreWallsForAttacks: Boolean,
    val ignoreWallsForSupers: Boolean,
    val holdAttack: Double,
)

object BrawlersInfo {

    private const val TAG = "BrawlersInfo"
    @Volatile private var cache: Map<String, BrawlerInfo>? = null

    fun load(): Map<String, BrawlerInfo> {
        cache?.let { return it }
        val file = PylaConfig.resolve("cfg/brawlers_info.json")
        if (!file.exists()) return emptyMap()
        val out = HashMap<String, BrawlerInfo>()
        try {
            val root = JSONObject(file.readText())
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                out[key] = BrawlerInfo(
                    safeRange = o.optDouble("safe_range", 0.0),
                    attackRange = o.optDouble("attack_range", 0.0),
                    superType = o.optString("super_type", "damage"),
                    superRange = o.optDouble("super_range", 0.0),
                    ignoreWallsForAttacks = o.optBoolean("ignore_walls_for_attacks", false),
                    ignoreWallsForSupers = o.optBoolean("ignore_walls_for_supers", false),
                    holdAttack = o.optDouble("hold_attack", 0.0),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load: ${t.message}")
        }
        cache = out
        return out
    }
}