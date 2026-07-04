package com.pyla.ai.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.engine.BotEngine
import com.pyla.ai.input.InputService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val pylaColors = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFFFC400),
                onPrimary = androidx.compose.ui.graphics.Color(0xFF241A00),
                secondary = androidx.compose.ui.graphics.Color(0xFF9C8CFF),
                background = androidx.compose.ui.graphics.Color(0xFF0B0E14),
                surface = androidx.compose.ui.graphics.Color(0xFF151B26),
                surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1F2733),
                onBackground = androidx.compose.ui.graphics.Color(0xFFE8ECF4),
                onSurface = androidx.compose.ui.graphics.Color(0xFFE8ECF4),
            )
            MaterialTheme(colorScheme = pylaColors) {
                Surface(modifier = Modifier.fillMaxSize()) {

                    var assetsReady by remember { mutableStateOf(PylaConfig.isReady()) }
                    LaunchedEffect(Unit) {
                        if (!assetsReady) {
                            withContext(Dispatchers.IO) { PylaConfig.init(applicationContext) }
                            assetsReady = true
                        }
                    }
                    BotControlScreen(
                        assetsReady = assetsReady,
                        accessibilityEnabled = ::isAccessibilityEnabled,
                        requestAccessibility = ::openAccessibilitySettings,
                        onMediaProjectionGranted = { code, data, w, h, queue ->
                            CaptureService.start(this, code, data, w, h)
                            startEngine(w, h, queue)
                        },
                        onStop = ::stopBot,
                    )
                }
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun startEngine(w: Int, h: Int, uiQueue: List<MutableMap<String, Any>>) {
        if (BotEngine.instance != null) return
        InputCoordinates.setScreenSize(w, h)
        val accessOk = isAccessibilityEnabled()
        android.util.Log.i("Pyla", "[MainActivity] startEngine: capture ${w}x${h} accessibility=$accessOk")
        if (!accessOk) {
            android.util.Log.w("Pyla", "[MainActivity] Accessibility not detected, starting anyway. Enable PylaAI in Settings > Accessibility if input doesn't work.")
        }
        val queue = if (uiQueue.isNotEmpty()) uiQueue.toMutableList()
        else com.pyla.ai.engine.BotEngine.loadBrawlerData()
        if (queue.isEmpty()) {
            val m = HashMap<String, Any>()
            m["brawler"] = "shelly"
            m["type"] = "trophies"
            m["trophies"] = 0
            m["wins"] = 0
            m["push_until"] = 1000
            m["automatically_pick"] = false
            m["win_streak"] = 0
            queue.add(m)
        }
        BotEngine(applicationContext, queue).start()
    }

    private fun stopBot() {
        BotEngine.instance?.stop()
        CaptureService.stop(this)
    }
}