package com.pyla.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
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
import com.pyla.ai.R
import com.pyla.ai.engine.BotEngine
import com.pyla.ai.engine.BotStatus
import com.pyla.ai.engine.Playstyles
import com.pyla.ai.input.InputService

private val PylaYellow = Color(0xFFFFC400)
private val PylaGreen = Color(0xFF4CD964)
private val PylaRed = Color(0xFFFF5A5A)
private val PylaBlue = Color(0xFF54A9FF)
private val PylaPurple = Color(0xFF9C8CFF)

private class QueueEntryUi(
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

@Composable
private fun SectionCard(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

@Composable
fun BotControlScreen(
    assetsReady: Boolean,
    accessibilityEnabled: () -> Boolean,
    requestAccessibility: () -> Unit,
    onMediaProjectionGranted: (resultCode: Int, data: Intent, width: Int, height: Int, queue: List<MutableMap<String, Any>>) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var accessibility by remember { mutableStateOf(accessibilityEnabled()) }
    var running by remember { mutableStateOf(BotStatus.engineRunning) }

    val prefs = remember { context.getSharedPreferences("pyla", Context.MODE_PRIVATE) }
    var playstyleId by remember { mutableStateOf(prefs.getString("playstyle", Playstyles.default.id) ?: Playstyles.default.id) }

    val queue = remember { mutableStateListOf<QueueEntryUi>() }
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
    fun persistQueue() {
        if (queueLoaded) BotEngine.saveBrawlerData(queue.map { it.toMap() })
    }

    var statusTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            statusTick++
            running = BotStatus.engineRunning
            delay(300)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            persistQueue()
            val metrics = context.resources.displayMetrics
            onMediaProjectionGranted(
                result.resultCode, result.data!!,
                metrics.widthPixels, metrics.heightPixels,
                queue.map { it.toMap() },
            )
            running = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_logo),
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
            )
            Column {
                Text("PylaAndroid", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = PylaYellow)
                Text("PylaAI Android Port", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionCard("Status", PylaBlue) {
            val stateColor = when (BotStatus.currentState) {
                "lobby" -> PylaGreen
                "match" -> PylaYellow
                "" -> Color.Gray
                else -> PylaBlue
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(stateColor.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        BotStatus.currentState.ifEmpty { "waiting..." },
                        style = MaterialTheme.typography.titleSmall,
                        color = stateColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (running) {
                    Text("RUNNING", style = MaterialTheme.typography.labelSmall, color = PylaGreen, fontWeight = FontWeight.Bold)
                }
            }
            StatusRow("Input", if (InputService.isConnected()) "connected" else "not connected", if (InputService.isConnected()) PylaGreen else PylaRed)
            StatusRow("Capture", BotStatus.captureSize.ifEmpty { "not started" })
            StatusRow("Frames", "${BotStatus.frameCount}")
            StatusRow("Last action", BotStatus.lastAction.ifEmpty { "none" })
            if (BotStatus.lastError.isNotEmpty()) {
                Text("ERROR: ${BotStatus.lastError}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = PylaRed)
            }
        }

        SectionCard("Playstyle", PylaPurple) {
            Playstyles.all.forEach { style ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = playstyleId == style.id,
                        enabled = !running,
                        onClick = {
                            playstyleId = style.id
                            prefs.edit().putString("playstyle", style.id).apply()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = PylaPurple),
                    )
                    Column {
                        Text(style.displayName, fontWeight = if (playstyleId == style.id) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            "modes: ${style.gamemodes.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("Brawler queue", PylaYellow) {
            if (!queueLoaded) {
                Text("Loading queue…", style = MaterialTheme.typography.bodySmall)
            }
            queue.forEachIndexed { index, entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = entry.brawler,
                                onValueChange = { entry.brawler = it; persistQueue() },
                                label = { Text("Brawler") },
                                singleLine = true,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !running && queue.size > 1,
                                onClick = { queue.removeAt(index); persistQueue() },
                            ) { Text("Remove", color = PylaRed) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Push:", style = MaterialTheme.typography.bodySmall)
                            FilterChip(
                                selected = entry.type == "trophies",
                                enabled = !running,
                                onClick = { entry.type = "trophies"; persistQueue() },
                                label = { Text("Trophies") },
                            )
                            FilterChip(
                                selected = entry.type == "wins",
                                enabled = !running,
                                onClick = { entry.type = "wins"; persistQueue() },
                                label = { Text("Wins") },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = entry.current,
                                onValueChange = { entry.current = it.filter(Char::isDigit); persistQueue() },
                                label = { Text(if (entry.type == "wins") "Current wins" else "Current trophies") },
                                singleLine = true,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = entry.pushUntil,
                                onValueChange = { entry.pushUntil = it.filter(Char::isDigit); persistQueue() },
                                label = { Text("Push until") },
                                singleLine = true,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(
                                checked = entry.autoPick,
                                enabled = !running,
                                onCheckedChange = { entry.autoPick = it; persistQueue() },
                                colors = SwitchDefaults.colors(checkedTrackColor = PylaGreen),
                            )
                            Column {
                                Text("Auto-pick brawler", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "Finds and selects the brawler in the menu by reading the screen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                enabled = queueLoaded && !running,
                onClick = { queue.add(QueueEntryUi()); persistQueue() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Add brawler") }
        }

        if (!accessibility) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = PylaRed.copy(alpha = 0.12f)),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accessibility service is off. It is required for the joystick and taps.", color = PylaRed, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            requestAccessibility()
                            accessibility = accessibilityEnabled()
                        }) { Text("Open settings") }
                        OutlinedButton(onClick = { accessibility = accessibilityEnabled() }) { Text("Refresh") }
                    }
                }
            }
        }

        if (!assetsReady) {
            Text("Preparing assets (first run only)…", color = PylaYellow, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PylaYellow)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = assetsReady && accessibility && !running,
                onClick = {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                },
                colors = ButtonDefaults.buttonColors(containerColor = PylaGreen, contentColor = Color(0xFF06280F)),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Start bot", fontWeight = FontWeight.Bold) }

            Button(
                enabled = running,
                onClick = { onStop(); running = false },
                colors = ButtonDefaults.buttonColors(containerColor = PylaRed, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Stop bot", fontWeight = FontWeight.Bold) }
        }

        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = { uriHandler.openUri(GITHUB_URL) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Open source on GitHub", color = PylaBlue)
        }
    }
}

private const val GITHUB_URL = "https://github.com/hyrsource/PylaAndroid"
