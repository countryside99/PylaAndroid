package com.pyla.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyla.ai.config.PylaUserSettings

private fun cfg(file: String) = "cfg/$file.toml"

private val allSettings = listOf(
    SettingDef("General", PylaColors.Blue, "general_config", listOf(
        SettingItem("run_for_minutes", "Run for minutes", "0 = unlimited", SettingType.Int),
        SettingItem("trophies_multiplier", "Trophies multiplier", "", SettingType.Int),
        SettingItem("brawl_stars_package", "Brawl Stars package", "", SettingType.String),
    )),
    SettingDef("Bot Behavior", PylaColors.Purple, "bot_config", listOf(
        SettingItem("minimum_movement_delay", "Min movement delay", "seconds", SettingType.Double),
        SettingItem("unstuck_movement_delay", "Unstuck movement delay", "seconds", SettingType.Double),
        SettingItem("unstuck_movement_hold_time", "Unstuck movement hold", "seconds", SettingType.Double),
        SettingItem("perceived_tile_size", "Perceived tile size", "pixels", SettingType.Int),
        SettingItem("centered_wall_detection", "Centered wall detection", "", SettingType.Bool),
        SettingItem("wall_detection_confidence", "Wall detection confidence", "0.0 - 1.0", SettingType.Double),
        SettingItem("entity_detection_confidence", "Entity detection confidence", "0.0 - 1.0", SettingType.Double),
        SettingItem("play_again_on_win", "Play again on win", "yes / no", SettingType.String),
        SettingItem("seconds_to_hold_attack_after_reaching_max", "Hold attack after max", "seconds", SettingType.Double),
    )),
    SettingDef("Ability Detection", PylaColors.Orange, "bot_config", listOf(
        SettingItem("gadget_pixels_minimum", "Gadget pixel threshold", "", SettingType.Double),
        SettingItem("hypercharge_pixels_minimum", "Hypercharge pixel threshold", "", SettingType.Double),
        SettingItem("super_pixels_minimum", "Super pixel threshold", "", SettingType.Double),
        SettingItem("idle_pixels_minimum", "Idle pixel threshold", "", SettingType.Double),
    )),
    SettingDef("Time Thresholds", PylaColors.Teal, "time_tresholds", listOf(
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

private data class SettingItem(val key: String, val label: String, val hint: String, val type: SettingType)
private data class SettingDef(val section: String, val accent: Color, val configFile: String, val items: List<SettingItem>) {
    val relativePath: String get() = cfg(configFile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenOverlayPermission: () -> Unit = {},
) {
    var refreshTrigger by remember { mutableStateOf(0) }
    fun refresh() { refreshTrigger++ }
    LaunchedEffect(refreshTrigger) { }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(60.dp))
        Text("Settings", style = titleLarge(), modifier = Modifier.padding(horizontal = 16.dp))
        Text("Overrides save instantly · restart bot to apply", color = PylaColors.TextLow, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(14.dp))
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (def in allSettings) {
                PylaCard {
                    SectionHeader(headerIcon(def.section), def.section, def.accent)
                    Spacer(Modifier.height(6.dp))
                    def.items.forEach { item ->
                        SettingRow(def.relativePath, item, def.accent, ::refresh)
                    }
                }
            }
            Text(
                "Settings are written directly to the TOML config files on disk. Changes take effect on next bot start.",
                style = MaterialTheme.typography.bodySmall,
                color = PylaColors.TextLow,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}

private fun headerIcon(section: String) = when (section) {
    "General" -> Icons.Default.Settings
    "Bot Behavior" -> Icons.Default.GridView
    "Ability Detection" -> Icons.Default.Speed
    "Time Thresholds" -> Icons.Default.AutoAwesome
    else -> Icons.Default.Settings
}

@Composable
private fun SettingRow(relativePath: String, item: SettingItem, accent: Color, onChanged: () -> Unit) {
    val overrideExists = remember { PylaUserSettings.has(relativePath, item.key) }
    val defaultVal = remember { PylaUserSettings.getDefaultString(relativePath, item.key) ?: "" }
    var overrideVal by remember { mutableStateOf(PylaUserSettings.getString(relativePath, item.key, null)) }

    Spacer(Modifier.height(4.dp))
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (item.hint.isNotEmpty()) {
                    Text(item.hint, style = MaterialTheme.typography.bodySmall, color = PylaColors.TextLow)
                }
            }
            if (overrideExists) {
                TextButton(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    onClick = { PylaUserSettings.remove(relativePath, item.key); overrideVal = null; onChanged() },
                ) { Text("Reset", style = MaterialTheme.typography.labelSmall, color = PylaColors.Red) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val currentValue = overrideVal ?: defaultVal
            when (item.type) {
                SettingType.Bool -> {
                    val checked = currentValue.lowercase() in setOf("true", "1", "yes")
                    Switch(
                        checked = checked,
                        onCheckedChange = { v -> PylaUserSettings.set(relativePath, item.key, v.toString()); overrideVal = v.toString(); onChanged() },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent),
                    )
                    Text(if (checked) "ON" else "OFF", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = if (checked) accent else PylaColors.TextLow)
                }
                else -> {
                    OutlinedTextField(
                        value = currentValue,
                        onValueChange = { v -> PylaUserSettings.set(relativePath, item.key, v); overrideVal = v; onChanged() },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = when (item.type) {
                            SettingType.Int -> KeyboardType.Number
                            SettingType.Double -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }),
                    )
                    if (!overrideExists && defaultVal.isNotEmpty()) {
                        Text(defaultVal, style = MaterialTheme.typography.bodySmall, color = PylaColors.TextLow)
                    }
                }
            }
        }
    }
}