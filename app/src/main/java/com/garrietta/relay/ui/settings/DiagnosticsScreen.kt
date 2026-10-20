package com.garrietta.relay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.garrietta.relay.CrashReporter
import com.garrietta.relay.data.model.isUnknownNumber
import com.garrietta.relay.data.transport.RcsPairingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostics (Garrett 2026-10-20): moved out of the main Settings list
 * into its own screen behind a single Settings row, and expanded with
 * app / session / inbox / storage stats. No logcat on Garrett's device —
 * this screen IS the diagnostic surface, so values are shown verbatim:
 * failures, ages and raw counts are never rounded away.
 *
 * Live transport + notification strings refresh on a 2s ticker while the
 * screen is visible (same as the old in-Settings card); the storage walk
 * runs off the main thread on entry and on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val rcsState by viewModel.rcsPairingState.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    // RCS transport + notification state, refreshed on a ticker while the
    // screen is visible — the sync-timeout debug round's diagnosability core.
    var transportState by remember { mutableStateOf(viewModel.transportDiagnostics()) }
    var notifState by remember { mutableStateOf(viewModel.notificationDiagnostics()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            transportState = viewModel.transportDiagnostics()
            notifState = viewModel.notificationDiagnostics()
        }
    }

    // Storage walk is blocking IO: run it off the main thread, once on
    // entry and again whenever the Refresh row is tapped.
    var storage by remember { mutableStateOf<SettingsViewModel.StorageStats?>(null) }
    var storageNonce by remember { mutableStateOf(0) }
    LaunchedEffect(storageNonce) {
        storage = withContext(Dispatchers.IO) { viewModel.storageStats() }
    }

    // Membership ground truth (Garrett 2026-10-20 deep dive): raw
    // field 20 / field 21 per server conversation, built off the main
    // thread on entry and on demand, like the storage walk.
    var membership by remember { mutableStateOf<String?>(null) }
    var membershipNonce by remember { mutableStateOf(0) }
    LaunchedEffect(membershipNonce) {
        membership = withContext(Dispatchers.IO) { viewModel.membershipDump() }
    }

    val appInfo = remember { viewModel.appInfo() }
    // Crash diagnosability: the app captures its own uncaught exceptions
    // (see CrashReporter) and surfaces the last one here.
    val lastCrash = remember { CrashReporter.lastCrashSummary(context) }
    val tachyonExpiry = viewModel.tachyonExpiryMs()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsCard(title = "RCS transport", icon = Icons.Filled.Warning) {
                SettingsRow(
                    title = "Transport",
                    subtitle = transportState,
                    actionLabel = "Refresh",
                    onAction = { transportState = viewModel.transportDiagnostics() },
                )
                SettingsRow(
                    title = "Pairing",
                    subtitle = when (rcsState) {
                        RcsPairingState.NOT_PAIRED -> "Not paired"
                        RcsPairingState.PAIRING -> "Pairing…"
                        RcsPairingState.PAIRED -> "Connected"
                    },
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Tachyon token",
                    subtitle = if (tachyonExpiry != null) {
                        "Expires ${formatTime(tachyonExpiry)} (${remainingText(tachyonExpiry)}) " +
                            "of a ~24h TTL"
                    } else {
                        "No token stored (not paired)"
                    },
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Server conversations",
                    subtitle = viewModel.rcsConversationCount().toString(),
                    actionLabel = null,
                    onAction = null,
                )
            }

            SettingsCard(title = "Notifications", icon = Icons.Filled.Notifications) {
                SettingsRow(
                    title = "Notifications",
                    subtitle = notifState,
                    actionLabel = "Refresh",
                    onAction = { notifState = viewModel.notificationDiagnostics() },
                )
            }

            SettingsCard(title = "App", icon = Icons.Filled.Info) {
                SettingsRow(
                    title = "Version",
                    subtitle = "${appInfo.versionName} (code ${appInfo.versionCode})",
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Build",
                    subtitle = "${appInfo.buildType} — ${appInfo.packageName}",
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Android",
                    subtitle = "${appInfo.androidRelease} (API ${appInfo.apiLevel})",
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Device",
                    subtitle = appInfo.device,
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Installed",
                    subtitle = "First ${formatTime(appInfo.firstInstallMs)} · " +
                        "updated ${formatTime(appInfo.lastUpdateMs)}",
                    actionLabel = null,
                    onAction = null,
                )
            }

            SettingsCard(title = "Inbox & storage", icon = null) {
                SettingsRow(
                    title = "Conversations",
                    subtitle = conversations.size.toString(),
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Self-sender contradictions",
                    subtitle = "selfSenderContradictions=${viewModel.selfSenderContradictionCount()}",
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Participant anomalies",
                    subtitle = "participantAnomalies=${viewModel.participantAnomalyCount()}" +
                        viewModel.participantAnomalyTitles()
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(separator = "; ", prefix = " — ")
                            .orEmpty(),
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Group chats",
                    subtitle = conversations.count { it.isGroup }.toString(),
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Unknown senders",
                    subtitle = conversations.count { it.isUnknownNumber() }.toString(),
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Media cache",
                    subtitle = storage?.let {
                        "${it.mediaFiles} files, ${formatBytes(it.mediaBytes)}"
                    } ?: "Measuring…",
                    actionLabel = null,
                    onAction = null,
                )
                SettingsRow(
                    title = "Chat history cache",
                    subtitle = storage?.let {
                        "${it.historyFiles} files, ${formatBytes(it.historyBytes)}"
                    } ?: "Measuring…",
                    actionLabel = "Refresh",
                    onAction = { storageNonce++ },
                )
            }

            SettingsCard(title = "Conversation membership", icon = null) {
                SettingsRow(
                    title = "Membership dump",
                    subtitle = "Raw participants (field 20) vs the server's " +
                        "membership list (field 21) per conversation, with " +
                        "the derived result the UI renders. Marked " +
                        "conversations first. No message bodies.",
                    actionLabel = "Refresh",
                    onAction = { membershipNonce++ },
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = membership ?: "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            if (lastCrash != null) {
                var dismissed by remember { mutableStateOf(false) }
                if (!dismissed) {
                    SettingsCard(title = "Crash", icon = Icons.Filled.Warning) {
                        SettingsRow(
                            title = "Last crash",
                            subtitle = lastCrash,
                            actionLabel = "Clear",
                            onAction = {
                                CrashReporter.clear(context)
                                dismissed = true
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

/** Remaining time until [expiryMs] (or how long ago it passed), verbatim. */
private fun remainingText(expiryMs: Long): String {
    val diffMs = expiryMs - System.currentTimeMillis()
    val absMin = kotlin.math.abs(diffMs) / 60_000
    val span = if (absMin >= 60) "${absMin / 60}h ${absMin % 60}m" else "${absMin}m"
    return if (diffMs >= 0) "$span left" else "expired $span ago"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
