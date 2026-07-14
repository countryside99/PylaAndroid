package com.pyla.ai.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object BrawlTrackerApi {

    private const val TAG = "BrawlTracker"
    private const val BRAWLTRACKER_URL = "https://brawltracker.com"

    data class BrawlerStats(
        val name: String,
        val trophies: Int,
        val powerLevel: Int,
    )

    suspend fun fetchPlayerBrawlers(playerTag: String): List<BrawlerStats> = withContext(Dispatchers.IO) {
        val tag = playerTag.trim().replace("#", "")
        if (tag.isEmpty()) return@withContext emptyList()
        try {
            val url = URL("${BRAWLTRACKER_URL}/api/player/${URLEncoder.encode(tag, "UTF-8")}")
            val json = httpGetJson(url) ?: return@withContext emptyList()
            val brawlers = json.optJSONArray("brawlers") ?: return@withContext emptyList()
            val out = mutableListOf<BrawlerStats>()
            for (i in 0 until brawlers.length()) {
                val b = brawlers.getJSONObject(i)
                out.add(BrawlerStats(
                    name = b.optString("name", "").lowercase(),
                    trophies = b.optInt("trophies", 0),
                    powerLevel = b.optInt("power", 0),
                ))
            }
            Log.i(TAG, "Fetched ${out.size} brawlers for tag #$tag from BrawlTracker")
            out
        } catch (e: Exception) {
            Log.w(TAG, "BrawlTracker fetch failed, trying Brawlify: ${e.message}")
            fetchBrawlifyPlayerBrawlers(tag)
        }
    }

    private suspend fun fetchBrawlifyPlayerBrawlers(tag: String): List<BrawlerStats> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.brawlify.com/v1/players/%23$tag")
            val json = httpGetJson(url) ?: return@withContext emptyList()
            val brawlers = json.optJSONArray("brawlers") ?: return@withContext emptyList()
            val out = mutableListOf<BrawlerStats>()
            for (i in 0 until brawlers.length()) {
                val b = brawlers.getJSONObject(i)
                out.add(BrawlerStats(
                    name = b.optString("name", "").lowercase(),
                    trophies = b.optInt("trophies", 0),
                    powerLevel = b.optInt("power", 0),
                ))
            }
            Log.i(TAG, "Fetched ${out.size} brawlers for tag #$tag from Brawlify")
            out
        } catch (e: Exception) {
            Log.e(TAG, "Brawlify fetch also failed: ${e.message}")
            emptyList()
        }
    }

    private fun httpGetJson(url: URL): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "PylaAndroid/1.1")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} from ${url.host}")
                return null
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) sb.append(line)
            reader.close()
            return JSONObject(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "HTTP GET failed: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
