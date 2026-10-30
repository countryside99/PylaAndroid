package com.pyla.ai.ui

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.pyla.PlaystyleImportFailure
import com.pyla.ai.pyla.PlaystyleImportResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyla.ai.config.PlaystyleSelection
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
    var selectedPlaystyle by remember(assetsReady) {
        mutableStateOf<String?>(null)
    }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    LaunchedEffect(assetsReady) {
        try {
            selectedPlaystyle = loadPlaystyleSelection(assetsReady)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            importFailed = true
            importMessage = "Playstyle settings could not be loaded. Check device storage and reopen this screen."
        }
    }
    // Do not allow a user choice to race the initial asynchronous resolution.
    val selectionReady = assetsReady && selectedPlaystyle != null
    var refresh by remember { mutableStateOf(0) }
    val playstyles = remember(assetsReady, refresh) {
        if (assetsReady) PlaystyleRegistry.listMeta() else emptyList()
    }
    val bundled = remember(assetsReady) {
        if (assetsReady) PlaystyleRegistry.bundledNames(context) else emptySet()
    }
    val importScope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            importing = false // Cancelling the picker changes neither files nor selection.
        } else if (runningProvider()) {
            importing = false
            importFailed = true
            importMessage = "Stop the bot before importing a playstyle."
        } else {
            importing = true
            importFailed = false
            importMessage = "Importing playstyle…"
            importScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val name = queryDisplayName(context, uri) ?: "custom.pyla"
                            PlaystyleRegistry.importDocument(name, context.contentResolver.openInputStream(uri))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PlaystyleImportResult.Failure(PlaystyleImportFailure.READ_FAILED)
                        }
                    }
                    when (result) {
                        is PlaystyleImportResult.Success -> {
                            refresh++ // Always show a saved file, even if selection persistence fails.
                            if (runningProvider()) {
                                importMessage = "Imported. Stop the bot to select the new playstyle."
                            } else {
                                try {
                                    selectedPlaystyle = withContext(Dispatchers.IO) {
                                        PlaystyleSelection.select(result.filename)
                                    }
                                    importMessage = "Playstyle imported and selected."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    importFailed = true
                                    importMessage = "Imported, but selection could not be saved. Select it manually."
                                }
                            }
                        }
                        is PlaystyleImportResult.Failure -> {
                            importFailed = true
                            importMessage = playstyleImportFailureText(result)
                            DiagnosticLog.failure("playstyle", "document_import", "import_${result.reason.name.lowercase(java.util.Locale.ROOT)}")
                        }
                    }
                } finally {
                    importing = false
                }
            }
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
                        enabled = selectionReady && !running && !importing,
                        onClick = {
                            try {
                                selectedPlaystyle = PlaystyleSelection.select(style.filename)
                                importFailed = false
                                importMessage = "Playstyle selected."
                            } catch (_: Exception) {
                                importFailed = true
                                importMessage = "Playstyle could not be selected. Check device storage."
                            }
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
                    if (selectionReady && !running && style.filename !in bundled) {
                        TextButton(
                            enabled = !importing,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                try {
                                    PlaystyleRegistry.delete(style.filename)
                                    selectedPlaystyle = PlaystyleSelection.onPlaystyleDeleted(style.filename)
                                } catch (_: Exception) {
                                    importFailed = true
                                    importMessage = "Playstyle settings could not be saved. Reopen this screen after checking storage."
                                } finally {
                                    refresh++
                                }
                            },
                        ) { Icon(Icons.Default.Delete, null, tint = PylaColors.Red, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton(
                label = if (importing) "Importing…" else "Import custom .pyla",
                icon = Icons.Default.Upload,
                accent = PylaColors.Teal,
                enabled = selectionReady && !running && !importing,
                onClick = {
                    importing = true
                    try {
                        importLauncher.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        importing = false
                        importFailed = true
                        importMessage = "Import failed: the document picker could not be opened."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            importMessage?.let { message ->
                Text(
                    message,
                    color = if (importFailed) PylaColors.Red else PylaColors.Teal,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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