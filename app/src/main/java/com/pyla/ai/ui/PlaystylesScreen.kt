package com.pyla.ai.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyla.ai.engine.BotEngine
import com.pyla.ai.pyla.PlaystyleRegistry

@Composable
fun PlaystylesScreen(
    assetsReady: Boolean,
    runningProvider: () -> Boolean,
    queue: MutableList<QueueEntryUi>,
    onQueueChanged: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pyla", Context.MODE_PRIVATE) }
    var selectedPlaystyle by remember {
        mutableStateOf(prefs.getString("playstyle", PlaystyleRegistry.DEFAULT_PLAYSTYLE) ?: PlaystyleRegistry.DEFAULT_PLAYSTYLE)
    }
    var refresh by remember { mutableStateOf(0) }
    val playstyles = remember(assetsReady, refresh) {
        if (assetsReady) PlaystyleRegistry.listMeta() else emptyList()
    }
    val bundled = remember(assetsReady) {
        if (assetsReady) PlaystyleRegistry.bundledNames(context) else emptySet()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val name = queryDisplayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "custom.pyla"
                if (content != null) {
                    val stored = PlaystyleRegistry.importContent(name, content)
                    if (stored != null) {
                        selectedPlaystyle = stored
                        prefs.edit().putString("playstyle", stored).apply()
                        refresh++
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val running by remember { androidx.compose.runtime.derivedStateOf(runningProvider) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(60.dp))
        Text("Playstyles", style = titleLarge())
        Text("Choose behaviour, manage the brawler queue", color = PylaColors.TextLow, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))

        PylaCard {
            SectionHeader(Icons.Default.AutoAwesome, "Playstyle", PylaColors.Purple)
            if (playstyles.isEmpty()) {
                Text(
                    if (assetsReady) "No .pyla playstyles found. Import one below." else "Loading playstyles…",
                    color = PylaColors.TextMed, fontSize = 13.sp,
                )
            }
            playstyles.forEach { style ->
                val selected = selectedPlaystyle == style.filename
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected,
                        enabled = !running,
                        onClick = {
                            selectedPlaystyle = style.filename
                            prefs.edit().putString("playstyle", style.filename).apply()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = PylaColors.Purple),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(style.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                            Icon(
                                if (style.filename !in bundled) Icons.Default.Upload else Icons.Default.Verified,
                                null,
                                tint = if (style.filename !in bundled) PylaColors.Teal else PylaColors.Purple.copy(alpha = 0.6f),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            "modes: ${style.gamemodes.joinToString(", ")}" + if (style.author.isNotBlank()) " · ${style.author}" else "",
                            color = PylaColors.TextLow, fontSize = 11.sp,
                        )
                    }
                    if (!running && style.filename !in bundled) {
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                PlaystyleRegistry.delete(style.filename)
                                if (selectedPlaystyle == style.filename) {
                                    selectedPlaystyle = PlaystyleRegistry.DEFAULT_PLAYSTYLE
                                    prefs.edit().putString("playstyle", selectedPlaystyle).apply()
                                }
                                refresh++
                            },
                        ) { Icon(Icons.Default.Delete, null, tint = PylaColors.Red, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton(
                label = "Import custom .pyla",
                icon = Icons.Default.Upload,
                accent = PylaColors.Teal,
                enabled = assetsReady && !running,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        PylaCard {
            SectionHeader(Icons.Default.GridView, "Brawler queue", PylaColors.Yellow)
            queue.forEachIndexed { index, entry ->
                QueueEntryCard(
                    entry = entry,
                    running = running,
                    size = queue.size,
                    persist = onQueueChanged,
                    onRemove = { queue.removeAt(index); onQueueChanged() },
                )
            }
            SecondaryButton(
                label = "Add brawler",
                icon = Icons.Default.Add,
                accent = PylaColors.Yellow,
                enabled = !running,
                onClick = { queue.add(QueueEntryUi()); onQueueChanged() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun QueueEntryCard(
    entry: QueueEntryUi,
    running: Boolean,
    size: Int,
    persist: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PylaColors.SurfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = entry.brawler,
                    onValueChange = { entry.brawler = it; persist() },
                    label = { Text("Brawler") },
                    singleLine = true, enabled = !running, modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !running && size > 1, onClick = onRemove) {
                    Icon(Icons.Default.Close, null, tint = PylaColors.Red, modifier = Modifier.size(16.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = entry.type == "trophies", enabled = !running,
                    onClick = { entry.type = "trophies"; persist() },
                    label = { Text("Trophies") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PylaColors.Yellow, selectedLabelColor = Color.Black),
                )
                FilterChip(
                    selected = entry.type == "wins", enabled = !running,
                    onClick = { entry.type = "wins"; persist() },
                    label = { Text("Wins") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PylaColors.Purple, selectedLabelColor = Color.Black),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = entry.current,
                    onValueChange = { entry.current = it.filter(Char::isDigit); persist() },
                    label = { Text(if (entry.type == "wins") "Wins" else "Trophies") },
                    singleLine = true, enabled = !running, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = entry.pushUntil,
                    onValueChange = { entry.pushUntil = it.filter(Char::isDigit); persist() },
                    label = { Text("Push until") },
                    singleLine = true, enabled = !running, modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = entry.autoPick, enabled = !running,
                    onCheckedChange = { entry.autoPick = it; persist() },
                    colors = SwitchDefaults.colors(checkedTrackColor = PylaColors.Green),
                )
                Column {
                    Text("Auto-pick brawler", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Selects the brawler in-game via OCR", color = PylaColors.TextLow, fontSize = 11.sp)
                }
            }
        }
    }
}