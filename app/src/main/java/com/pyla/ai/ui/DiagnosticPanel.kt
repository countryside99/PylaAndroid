package com.pyla.ai.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pyla.ai.diagnostics.DiagnosticExport
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.diagnostics.DiagnosticSnapshot
import java.io.File
import kotlinx.coroutines.delay

/**
 * Event Log card: a bounded recent-log view plus local export. The app has no network access;
 * the redacted report only leaves the device as a file the user saves or shares themselves.
 */
@Composable
fun DiagnosticPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    // Frozen when the user presses Save, so the file holds what was on screen at that moment
    // even though the system file picker returns later.
    var pendingSave by remember { mutableStateOf<DiagnosticSnapshot?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DiagnosticExport.MIME_TYPE),
    ) { uri: Uri? ->
        val snapshot = pendingSave
        pendingSave = null
        status = when {
            uri == null || snapshot == null -> ExportStatus.Idle // picker cancelled: nothing written
            saveTo(context, uri, snapshot) -> ExportStatus.Saved
            else -> ExportStatus.Failed
        }
    }

    // Low-rate periodic refresh of bounded line copies only.
    LaunchedEffect(Unit) {
        while (true) {
            lines = DiagnosticLog.recentLines(RECENT_LINE_LIMIT)
            delay(REFRESH_MILLIS)
        }
    }

    PylaCard(modifier = modifier) {
        SectionHeader(Icons.Default.Article, "Event log", PylaColors.Teal)
        Text(
            "Kept in memory only. Cleared when the app process restarts.",
            color = PylaColors.TextLow,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PylaColors.TextLow.copy(alpha = 0.08f))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            if (lines.isEmpty()) {
                Text("No events recorded yet.", color = PylaColors.TextLow, fontSize = 11.sp)
            } else {
                lines.forEach { line ->
                    Text(
                        line,
                        color = PylaColors.TextMed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                label = "Save log",
                icon = Icons.Default.Save,
                accent = PylaColors.Blue,
                onClick = {
                    lines = DiagnosticLog.recentLines(RECENT_LINE_LIMIT)
                    status = ExportStatus.Idle
                    val snapshot = DiagnosticLog.snapshot()
                    pendingSave = snapshot
                    runCatching { saveLauncher.launch(DiagnosticExport.fileName(System.currentTimeMillis())) }
                        .onFailure { pendingSave = null; status = ExportStatus.Failed }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton(
                label = "Share log",
                icon = Icons.Default.Share,
                accent = PylaColors.Blue,
                onClick = {
                    lines = DiagnosticLog.recentLines(RECENT_LINE_LIMIT)
                    status = if (share(context, DiagnosticLog.snapshot())) ExportStatus.Idle else ExportStatus.Failed
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "The redacted log is written to a file. Nothing leaves this device unless you send " +
                "that file yourself; review it before posting it publicly.",
            color = PylaColors.TextLow,
            fontSize = 11.sp,
        )
        when (status) {
            ExportStatus.Idle -> Unit
            ExportStatus.Saved -> {
                Spacer(Modifier.height(4.dp))
                Text("Log saved", color = PylaColors.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            ExportStatus.Failed -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "The log could not be exported. It is still here; try again.",
                    color = PylaColors.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private enum class ExportStatus { Idle, Saved, Failed }

/** Writes the frozen snapshot to the document the user picked. No message or path is logged. */
private fun saveTo(context: Context, uri: Uri, snapshot: DiagnosticSnapshot): Boolean = try {
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("no stream")
    stream.use { DiagnosticExport.write(snapshot, it) }
    true
} catch (_: Throwable) {
    false
}

/**
 * Hands the snapshot to the system share sheet as a file from the app cache, readable only by the
 * app the user picks. Returns false when the file or the share sheet is unavailable.
 */
private fun share(context: Context, snapshot: DiagnosticSnapshot): Boolean = try {
    val file = DiagnosticExport.writeShareFile(File(context.cacheDir, "diagnostics"), snapshot, System.currentTimeMillis())
    val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostics", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = DiagnosticExport.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share diagnostic log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Throwable) {
    false
}

private const val RECENT_LINE_LIMIT = 80
private const val REFRESH_MILLIS = 1000L