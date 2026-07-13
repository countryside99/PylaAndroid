package com.pyla.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pyla.ai.config.PylaUserSettings

private val PylaYellow = Color(0xFFFFC400)
private val PylaGreen = Color(0xFF4CD964)
private val PylaRed = Color(0xFFFF5A5A)
private val PylaBlue = Color(0xFF54A9FF)
private val PylaPurple = Color(0xFF9C8CFF)
private val PylaOrange = Color(0xFFFF9500)
private val PylaTeal = Color(0xFF5AC8FA)

private fun cfg(file: String) = "cfg/$file.toml"

private val allSettings = listOf(
    SettingDef("General", PylaBlue, "general_config", listOf(
        SettingItem("run_for_minutes", "Run for minutes", "0 = unlimited", SettingType.Int),
        SettingItem("trophies_multiplier", "Trophies multiplier", "", SettingType.Int),
        SettingItem("brawl_stars_package", "Brawl Stars package", "", SettingType.String),
    )),
    SettingDef("Bot Behavior", PylaPurple, "bot_config", listOf(
        SettingItem("minimum_movement_delay", "Min movement delay", "seconds", SettingType.Double),
        SettingItem("unstuck_movement_delay", "Unstuck movement delay", "seconds", SettingType.Double),
        SettingItem("unstuck_movement_hold_time", "Unstuck movement hold", "seconds", SettingType.Double),
        SettingItem("perceived_tile_size", "Perceived tile size", "pixels", SettingType.Int),
        SettingItem("centered_wall_detection", "Centered wall detection", "", SettingType.Bool),
        SettingItem("wall_detection_confidence", "Wall detection confidence", "0.0 - 1.0", SettingType.Double),
        SettingItem("entity_detection_confidence", "Entity detection confidence", "0.0 - 1.0", SettingType.Double),
        SettingItem("play_again_on_win", "Play again on win", "", SettingType.String),
        SettingItem("seconds_to_hold_attack_after_reaching_max", "Hold attack after max", "seconds", SettingType.Double),
    )),
    SettingDef("Ability Detection", PylaOrange, "bot_config", listOf(
        SettingItem("gadget_pixels_minimum", "Gadget pixel threshold", "", SettingType.Double),
        SettingItem("hypercharge_pixels_minimum", "Hypercharge pixel threshold", "", SettingType.Double),
        SettingItem("super_pixels_minimum", "Super pixel threshold", "", SettingType.Double),
        SettingItem("idle_pixels_minimum", "Idle pixel threshold", "", SettingType.Double),
    )),
    SettingDef("Time Thresholds", PylaTeal, "time_tresholds", listOf(
        SettingItem("state_check", "State check interval", "seconds", SettingType.Double),
        SettingItem("no_detections", "No detections timeout", "seconds", SettingType.Double),
        SettingItem("idle", "Idle check interval", "seconds", SettingType.Double),
        SettingItem("gadget", "Gadget check interval", "seconds", SettingType.Double),
        SettingItem("hypercharge", "Hypercharge check interval", "seconds", SettingType.Double),
        SettingItem("super", "Super check interval", "seconds", SettingType.Double),
        SettingItem("wall_detection", "Wall detection interval", "seconds", SettingType.Double),
        SettingItem("no_detection_proceed", "No detection proceed", "seconds", SettingType.Double),
        SettingItem("check_if_brawl_stars_crashed", "Check crash interval", "seconds", SettingType.Int),
    )),
)

private enum class SettingType { Bool, Int, Double, String }

private data class SettingItem(
    val key: String, val label: String, val hint: String, val type: SettingType,
)

private data class SettingDef(
    val section: String, val accent: Color, val configFile: String, val items: List<SettingItem>,
) {
    val relativePath: String get() = cfg(configFile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var refreshTrigger by remember { mutableStateOf(0) }
    fun refresh() { refreshTrigger++ }
    LaunchedEffect(refreshTrigger) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold)
                        Text("Overrides saved instantly — restart bot to apply",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { PylaUserSettings.clearAll(); refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset all to defaults")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (def in allSettings) {
                SectionCard(def.section, def.accent) {
                    def.items.forEach { item -> SettingRow(def.relativePath, item, def.accent, ::refresh) }
                }
            }
            Text("Settings are written directly to the TOML config files on disk. Changes take effect on next bot start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingRow(relativePath: String, item: SettingItem, accent: Color, onChanged: () -> Unit) {
    val overrideExists = remember { PylaUserSettings.has(relativePath, item.key) }
    val defaultVal = remember { PylaUserSettings.getDefaultString(relativePath, item.key) ?: "" }
    var overrideVal by remember { mutableStateOf(PylaUserSettings.getString(relativePath, item.key, null)) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (overrideExists) accent.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (item.hint.isNotEmpty())
                        Text(item.hint, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (overrideExists) {
                    TextButton(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        onClick = { PylaUserSettings.remove(relativePath, item.key); overrideVal = null; onChanged() }
                    ) { Text("Reset", style = MaterialTheme.typography.labelSmall, color = PylaRed) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val currentValue = overrideVal ?: defaultVal
                when (item.type) {
                    SettingType.Bool -> {
                        val checked = currentValue.lowercase() in setOf("true", "1", "yes")
                        Switch(checked = checked, onCheckedChange = { v ->
                            PylaUserSettings.set(relativePath, item.key, v.toString())
                            overrideVal = v.toString(); onChanged()
                        }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                        Text(if (checked) "ON" else "OFF", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        OutlinedTextField(value = currentValue, onValueChange = { v ->
                            PylaUserSettings.set(relativePath, item.key, v); overrideVal = v; onChanged()
                        }, singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = when (item.type) {
                                SettingType.Int -> KeyboardType.Number
                                SettingType.Double -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            }))
                        if (!overrideExists && defaultVal.isNotEmpty())
                            Text(defaultVal, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
