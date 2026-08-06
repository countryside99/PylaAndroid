package com.pyla.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyla.ai.R
import com.pyla.ai.engine.BotStatus
import com.pyla.ai.input.InputService
import com.pyla.ai.overlay.OverlayPreferences
import com.pyla.ai.overlay.OverlayService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    assetsReady: Boolean,
    accessibilityEnabled: () -> Boolean,
    requestAccessibility: () -> Unit,
    onMediaProjectionGranted: (resultCode: Int, data: Intent, width: Int, height: Int, queue: List<MutableMap<String, Any>>) -> Unit,
    onStop: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    startRequest: Int,
    onStartRequestConsumed: () -> Unit,
    onOpenPlaystyles: () -> Unit,
    onOpenSettings: () -> Unit,
    provideQueue: () -> List<QueueEntryUi>,
) {
    val context = LocalContext.current
    var accessibility by remember { mutableStateOf(accessibilityEnabled()) }
    var running by remember { mutableStateOf(BotStatus.engineRunning) }
    var statusTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            running = BotStatus.engineRunning
            accessibility = accessibilityEnabled()
            statusTick++
            delay(400)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val metrics = context.resources.displayMetrics
            onMediaProjectionGranted(
                result.resultCode, result.data!!,
                metrics.widthPixels, metrics.heightPixels,
                provideQueue().map { it.toMap() },
            )
            running = true
        }
    }

    LaunchedEffect(startRequest, assetsReady, accessibility, running) {
        if (startRequest > 0 && assetsReady && accessibility && !running) {
            onStartRequestConsumed()
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PylaAndroid", style = titleLarge())
                Text("PylaAI Android Port", color = PylaColors.TextLow, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(18.dp))

        // Status card
        PylaCard {
            val stateColor = when (BotStatus.currentState) {
                "lobby" -> PylaColors.Green
                "match" -> PylaColors.Yellow
                "" -> PylaColors.TextLow
                else -> PylaColors.Blue
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Speed, null, tint = stateColor, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(BotStatus.currentState.ifEmpty { "Idle" }, style = titleMedium(), color = stateColor)
                    Text(
                        if (running) "Running" else "Stopped",
                        color = if (running) PylaColors.Green else PylaColors.TextLow,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
                StatusPill(
                    text = if (InputService.isConnected()) "Input OK" else "No input",
                    color = if (InputService.isConnected()) PylaColors.Green else PylaColors.Red,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (!accessibility) {
            PylaCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, null, tint = PylaColors.Red, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Accessibility is off", color = PylaColors.Red, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Required for joystick & taps", color = PylaColors.TextMed, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Open settings", null, PylaColors.Red, onClick = {
                        requestAccessibility(); accessibility = accessibilityEnabled()
                    })
                    SecondaryButton("Refresh", null, PylaColors.TextMed, onClick = { accessibility = accessibilityEnabled() })
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Primary controls
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                PrimaryButton(
                    label = if (running) "Running…" else "Start bot",
                    icon = Icons.Default.PlayArrow,
                    accent = PylaColors.Green,
                    enabled = assetsReady && accessibility && !running,
                    onClick = {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(mgr.createScreenCaptureIntent())
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            OverlayToggle(onOpenOverlayPermission = onOpenOverlayPermission)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            label = "Stop bot",
            icon = Icons.Default.Stop,
            accent = PylaColors.Red,
            enabled = running,
            onClick = { onStop(); running = false },
            modifier = Modifier.fillMaxWidth(),
        )

        if (!assetsReady) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PylaColors.Yellow)
            Text("Preparing assets (first run only)…", color = PylaColors.TextMed, fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))

        // Session info
        PylaCard {
            SectionHeader(Icons.Default.Speed, "Session", PylaColors.Blue)
            KeyValueRow("Capture", BotStatus.captureSize.ifBlank { "not started" })
            KeyValueRow("Frames", "${BotStatus.frameCount}")
            KeyValueRow("Last action", BotStatus.lastAction.ifBlank { "none" }, PylaColors.TextHigh)
            if (BotStatus.lastError.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(BotStatus.lastError, color = PylaColors.Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Quick actions
        PylaCard {
            SectionHeader(Icons.Default.ArrowOutward, "Quick actions", PylaColors.Purple)
            SecondaryButton("Manage playstyles", null, PylaColors.Purple, enabled = !running, onClick = onOpenPlaystyles, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Open settings", null, PylaColors.TextMed, onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(20.dp))
        val uriHandler = LocalUriHandler.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { uriHandler.openUri(GITHUB_URL) }) {
                Icon(Icons.Default.ArrowOutward, null, tint = PylaColors.Blue, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open source on GitHub", color = PylaColors.Blue, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun OverlayToggle(onOpenOverlayPermission: () -> Unit) {
    val context = LocalContext.current
    var overlayEnabled by remember { mutableStateOf(OverlayPreferences.isEnabled(context) && OverlayPreferences.canDrawOverlays(context)) }
    var permissionOk by remember { mutableStateOf(OverlayPreferences.canDrawOverlays(context)) }
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val allowed = OverlayPreferences.canDrawOverlays(context)
            permissionOk = allowed
            if (waiting && allowed) {
                waiting = false
                overlayEnabled = true
                OverlayPreferences.setEnabled(context, true)
                OverlayService.start(context)
            }
            delay(500)
        }
    }
    val accent = if (overlayEnabled) PylaColors.Teal else PylaColors.TextLow
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = if (overlayEnabled) 0.16f else 0.06f))
                .border(1.dp, accent.copy(alpha = if (overlayEnabled) 0.45f else 0.20f), RoundedCornerShape(14.dp))
                .clickable {
                    if (!overlayEnabled) {
                        if (permissionOk) {
                            overlayEnabled = true
                            OverlayPreferences.setEnabled(context, true)
                            OverlayService.start(context)
                        } else {
                            waiting = true
                            onOpenOverlayPermission()
                        }
                    } else {
                        overlayEnabled = false
                        waiting = false
                        OverlayPreferences.setEnabled(context, false)
                        OverlayService.stop(context)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PictureInPicture, contentDescription = "Floating overlay", tint = accent, modifier = Modifier.size(22.dp))
        }
        Text("Overlay", color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}