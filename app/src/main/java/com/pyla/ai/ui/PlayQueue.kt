package com.pyla.ai.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

const val GITHUB_URL = "https://github.com/countryside99/PylaAndroid"

/** Editable model for one brawler in the play queue. */
class QueueEntryUi(
    brawler: String = "shelly",
    type: String = "trophies",
    pushUntil: String = "1000",
    current: String = "0",
    autoPick: Boolean = false,
    winStreak: Int = 0,
) {
    var brawler by mutableStateOf(brawler)
    var type by mutableStateOf(type)
    var pushUntil by mutableStateOf(pushUntil)
    var current by mutableStateOf(current)
    var autoPick by mutableStateOf(autoPick)
    var winStreak = winStreak

    fun toMap(): MutableMap<String, Any> {
        val cur = current.toIntOrNull() ?: 0
        return hashMapOf(
            "brawler" to brawler.trim().lowercase().ifBlank { "shelly" },
            "type" to type,
            "trophies" to (if (type == "trophies") cur else 0),
            "wins" to (if (type == "wins") cur else 0),
            "push_until" to (pushUntil.toIntOrNull() ?: 1000),
            "automatically_pick" to autoPick,
            "win_streak" to winStreak,
        )
    }

    companion object {
        fun from(m: Map<String, Any>): QueueEntryUi {
            val type = (m["type"]?.toString() ?: "trophies").lowercase().let { if (it == "wins") "wins" else "trophies" }
            val cur = if (type == "wins") m["wins"] else m["trophies"]
            return QueueEntryUi(
                brawler = m["brawler"]?.toString() ?: "shelly",
                type = type,
                pushUntil = (m["push_until"] ?: 1000).toString(),
                current = (cur ?: 0).toString(),
                autoPick = m["automatically_pick"]?.toString()?.lowercase() in setOf("true", "1"),
                winStreak = m["win_streak"]?.toString()?.toIntOrNull() ?: 0,
            )
        }
    }
}

fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }
}