package com.pyla.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyla.ai.BuildConfig
import com.pyla.ai.diagnostics.ConsentRequest
import com.pyla.ai.diagnostics.ConsentState
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.diagnostics.DiagnosticReportReference
import com.pyla.ai.diagnostics.DiagnosticSubmissionCoordinator
import com.pyla.ai.diagnostics.DiagnosticUploadConfig
import com.pyla.ai.diagnostics.DiagnosticUploader
import com.pyla.ai.diagnostics.HttpUrlDiagnosticTransport
import com.pyla.ai.diagnostics.SubmissionStatus
import kotlinx.coroutines.delay

/**
 * One coordinator per process, owned by the Application scope rather than by any
 * Activity. A configuration change or Activity recreation re-attaches to the
 * same coordinator, so the frozen snapshot and an in-flight upload are never
 * duplicated and nothing is ever re-confirmed implicitly.
 */
object DiagnosticPanelController {
    @Volatile
    private var coordinator: DiagnosticSubmissionCoordinator? = null

    fun get(): DiagnosticSubmissionCoordinator {
        coordinator?.let { return it }
        return synchronized(this) {
            coordinator ?: buildCoordinator().also { coordinator = it }
        }
    }

    private fun buildCoordinator(): DiagnosticSubmissionCoordinator =
        DiagnosticSubmissionCoordinator(
            snapshotProvider = { DiagnosticLog.snapshot() },
            uploaderProvider = { defaultUploader() },
        )

    internal fun resetForTest() {
        synchronized(this) { coordinator?.close(); coordinator = null }
    }
}

private val privateDiagnosticTransport by lazy { HttpUrlDiagnosticTransport() }

private fun defaultUploader(): DiagnosticUploader? {
    val url = BuildConfig.DIAGNOSTIC_URL
    val token = BuildConfig.DIAGNOSTIC_UPLOAD_TOKEN
    if (url.isBlank() || token.isBlank()) return null
    return runCatching {
        DiagnosticUploader(
            config = DiagnosticUploadConfig(
                endpoint = url,
                uploadToken = token,
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                androidApi = android.os.Build.VERSION.SDK_INT,
            ),
            transport = privateDiagnosticTransport,
        )
    }.getOrNull()
}

/**
 * Event Log card: a bounded recent-log view plus the consent-gated
 * `Sent Log` button, its `Confirm`/`Cancel` dialog and a truthful status line.
 */
@Composable
fun DiagnosticPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coordinator = remember { DiagnosticPanelController.get() }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf<ConsentRequest?>(null) }
    var status by remember { mutableStateOf<SubmissionStatus>(SubmissionStatus.Idle) }

    // Low-rate periodic refresh. Only bounded line copies and a cheap state read
    // happen here; snapshot serialization and network I/O run on the worker.
    LaunchedEffect(Unit) {
        while (true) {
            lines = DiagnosticLog.recentLines(RECENT_LINE_LIMIT)
            status = coordinator.status()
            // Restore-after-recreate: a dialog that is still awaiting a decision
            // reappears with the SAME frozen snapshot and token. This never
            // confirms anything by itself - the user must still press Confirm.
            val live = coordinator.consentState()
            val restored = if (live is ConsentState.AwaitingConsent) {
                ConsentRequest(live.token, live.snapshot.reportId, live.snapshot.byteCount)
            } else {
                null
            }
            if (pending?.token != restored?.token) pending = restored
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
        SecondaryButton(
            label = "Sent Log",
            icon = null,
            accent = PylaColors.Blue,
            enabled = status !is SubmissionStatus.Sending,
            onClick = {
                lines = DiagnosticLog.recentLines(RECENT_LINE_LIMIT)
                // A null result means a dialog is already open or an attempt is
                // in flight. Never clobber an open dialog in that case; it stays
                // bound to its own frozen snapshot and token.
                val opened = coordinator.requestConsent()
                if (opened != null) pending = opened
                status = coordinator.status()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        when (val s = status) {
            is SubmissionStatus.Idle -> Unit
            is SubmissionStatus.Sending ->
                Text("Sending diagnostic log…", color = PylaColors.TextMed, fontSize = 12.sp)
            is SubmissionStatus.Failed ->
                Text(s.reason, color = PylaColors.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            is SubmissionStatus.Submitted -> {
                Text(
                    "Log submitted",
                    color = PylaColors.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                val reference = DiagnosticReportReference.fromReportId(s.reportId)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        reference,
                        color = PylaColors.TextMed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { copyReportId(context, reference) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = PylaColors.Blue,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Copy ID", color = PylaColors.Blue, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    val request = pending
    if (request != null) {
        AlertDialog(
            onDismissRequest = {
                // Back and outside-touch consume the consent atomically and send
                // nothing; a later callback can never confirm this snapshot.
                coordinator.dismiss(request.token)
                pending = null
            },
            confirmButton = {
                TextButton(onClick = {
                    val token = request.token
                    pending = null
                    if (coordinator.confirm(token)) status = SubmissionStatus.Sending
                    else status = coordinator.status()
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = {
                    coordinator.cancel(request.token)
                    pending = null
                }) { Text("Cancel") }
            },
            title = { Text("Submit diagnostic log?", color = PylaColors.TextHigh) },
            text = {
                Column {
                    Text(
                        "Your redacted PylaAndroid event log, app version, Android API level, " +
                            "device model, screen size and CPU/RAM class are sent to " +
                            "pyla-logs.fetzi-ai.de and stored on Cloudflare. " +
                            "Reports expire after 7 days. Anyone with your report ID can view " +
                            "the report after human verification; review it before sharing the ID.",
                        color = PylaColors.TextMed,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Report ${request.reportId} · ${request.byteCount} bytes. " +
                            "Nothing is sent until you confirm.",
                        color = PylaColors.TextLow,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            containerColor = PylaColors.Surface,
        )
    }
}

/** Copies the exact report ID. Its holder may read the report through the public
 * viewer after human verification; never imply that the ID is safe to post publicly. */
private fun copyReportId(context: Context, reportId: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Pyla diagnostic report ID", reportId))
    }
}

private const val RECENT_LINE_LIMIT = 80
private const val REFRESH_MILLIS = 1000L