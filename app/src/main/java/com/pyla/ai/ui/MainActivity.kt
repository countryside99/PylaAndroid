package com.pyla.ai.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.engine.BotEngine
import com.pyla.ai.input.InputService
import com.pyla.ai.overlay.OverlayPreferences
import com.pyla.ai.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PylaTab(val label: String) { HOME("Home"), PLAYSTYLES("Playstyles"), SETTINGS("Settings") }

class MainActivity : ComponentActivity() {

    private val startRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_REQUEST_START) startRequest.intValue++

        setContent {
            val scheme = darkColorScheme(
                primary = PylaColors.Yellow,
                onPrimary = Color(0xFF241A00),
                secondary = PylaColors.Purple,
                background = PylaColors.Background,
                surface = PylaColors.Surface,
                surfaceVariant = PylaColors.SurfaceVariant,
                onBackground = PylaColors.TextHigh,
                onSurface = PylaColors.TextHigh,
                onSurfaceVariant = PylaColors.TextMed,
                outline = PylaColors.Outline,
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = PylaColors.Background) {
                    var assetsReady by remember { mutableStateOf(PylaConfig.isReady()) }
                    LaunchedEffect(Unit) {
                        if (!assetsReady) {
                            withContext(Dispatchers.IO) { PylaConfig.init(applicationContext) }
                            assetsReady = true
                        }
                    }

                    // Shared brawler queue, loaded once and edited on the Playstyles tab.
                    val queue = androidx.compose.runtime.mutableStateListOf<QueueEntryUi>()
                    var queueLoaded by remember { mutableStateOf(false) }
                    LaunchedEffect(assetsReady) {
                        if (assetsReady && !queueLoaded) {
                            val saved = BotEngine.loadBrawlerData()
                            queue.clear()
                            if (saved.isEmpty()) queue.add(QueueEntryUi())
                            else saved.forEach { queue.add(QueueEntryUi.from(it)) }
                            queueLoaded = true
                        }
                    }
                    fun persistQueue(): Unit = if (queueLoaded) BotEngine.saveBrawlerData(queue.map { it.toMap() }) else Unit

                    var tab by remember { mutableStateOf(PylaTab.HOME) }
                    Scaffold(
                        containerColor = PylaColors.Background,
                        bottomBar = { PylaBottomBar(tab) { tab = it } },
                    ) { inner ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(PylaColors.Background)
                                .padding(inner),
                        ) {
                            when (tab) {
                                PylaTab.HOME -> HomeScreen(
                                    assetsReady = assetsReady,
                                    accessibilityEnabled = ::isAccessibilityEnabled,
                                    requestAccessibility = ::openAccessibilitySettings,
                                    onMediaProjectionGranted = { code, data, w, h, _ ->
                                        CaptureService.start(this@MainActivity, code, data, w, h)
                                        startEngine(w, h, queue.map { it.toMap() })
                                        if (OverlayPreferences.isEnabled(this@MainActivity) && OverlayPreferences.canDrawOverlays(this@MainActivity)) {
                                            OverlayService.start(this@MainActivity)
                                        }
                                    },
                                    onStop = ::stopBot,
                                    onOpenOverlayPermission = ::openOverlayPermission,
                                    startRequest = startRequest.intValue,
                                    onStartRequestConsumed = { startRequest.intValue = 0 },
                                    onOpenPlaystyles = { tab = PylaTab.PLAYSTYLES },
                                    onOpenSettings = { tab = PylaTab.SETTINGS },
                                    provideQueue = { queue.toList() },
                                )
                                PylaTab.PLAYSTYLES -> PlaystylesScreen(
                                    assetsReady = assetsReady,
                                    runningProvider = { BotEngine.instance != null },
                                    queue = queue,
                                    onQueueChanged = { persistQueue() },
                                )
                                PylaTab.SETTINGS -> SettingsScreen(
                                    onOpenOverlayPermission = ::openOverlayPermission,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PylaBottomBar(selected: PylaTab, onSelect: (PylaTab) -> Unit) {
        NavigationBar(containerColor = PylaColors.Surface) {
            PylaTab.entries.forEach { t ->
                val icon = when (t) {
                    PylaTab.HOME -> Icons.Default.Home
                    PylaTab.PLAYSTYLES -> Icons.Default.SportsEsports
                    PylaTab.SETTINGS -> Icons.Default.Settings
                }
                NavigationBarItem(
                    selected = selected == t,
                    onClick = { onSelect(t) },
                    icon = { Icon(icon, contentDescription = t.label) },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PylaColors.Yellow,
                        selectedTextColor = PylaColors.Yellow,
                        indicatorColor = PylaColors.Yellow.copy(alpha = 0.16f),
                        unselectedIconColor = PylaColors.TextLow,
                        unselectedTextColor = PylaColors.TextLow,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REQUEST_START) startRequest.intValue++
    }

    override fun onResume() {
        super.onResume()
        if (OverlayPreferences.isEnabled(this)) {
            if (OverlayPreferences.canDrawOverlays(this)) OverlayService.start(this)
            else {
                OverlayPreferences.setEnabled(this, false)
                OverlayService.stop(this)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:$packageName") })
    }

    private fun startEngine(w: Int, h: Int, uiQueue: List<MutableMap<String, Any>>) {
        if (BotEngine.instance != null) return
        InputCoordinates.setScreenSize(w, h)
        val accessOk = isAccessibilityEnabled()
        android.util.Log.i("Pyla", "[MainActivity] startEngine: capture ${w}x${h} accessibility=$accessOk")
        val queue = if (uiQueue.isNotEmpty()) uiQueue.toMutableList() else BotEngine.loadBrawlerData()
        if (queue.isEmpty()) {
            val m = HashMap<String, Any>()
            m["brawler"] = "shelly"; m["type"] = "trophies"; m["trophies"] = 0; m["wins"] = 0
            m["push_until"] = 1000; m["automatically_pick"] = false; m["win_streak"] = 0
            queue.add(m)
        }
        BotEngine(applicationContext, queue).start()
    }

    private fun stopBot() {
        BotEngine.instance?.stop()
        CaptureService.stop(this)
    }

    companion object { const val ACTION_REQUEST_START = "com.pyla.ai.overlay.REQUEST_START" }
}