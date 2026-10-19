package com.garrietta.relay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garrietta.relay.CrashReporter
import com.garrietta.relay.data.model.ThemeMode
import com.garrietta.relay.data.model.isUnknownNumber
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.data.transport.RcsPairingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenRcsPairing: () -> Unit = {},
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val rcsState by viewModel.rcsPairingState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            SettingsCard(title = "Appearance", icon = null) {
                ThemeModeRow(
                    selected = themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }

            SettingsCard(title = "Texting", icon = Icons.Filled.Send) {
                val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Message notifications", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Notify when new messages arrive in Relay",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                    )
                }
                SettingsRow(
                    title = "Notification settings",
                    subtitle = "Sounds, badges, and previews",
                    actionLabel = "Open",
                    onAction = viewModel::openNotificationSettings,
                )
            }

            CategoriesCard(viewModel = viewModel)

            SettingsCard(title = "RCS", icon = Icons.Filled.Notifications) {
                SettingsRow(
                    title = "RCS messaging",
                    subtitle = when (rcsState) {
                        RcsPairingState.NOT_PAIRED -> "Not paired — pair with your Google account"
                        RcsPairingState.PAIRING -> "Pairing…"
                        RcsPairingState.PAIRED -> "Connected"
                    },
                    actionLabel = if (rcsState == RcsPairingState.NOT_PAIRED) "Pair" else null,
                    onAction = if (rcsState == RcsPairingState.NOT_PAIRED) onOpenRcsPairing else null,
                )
                Text(
                    "RCS will pair with Google Messages on this phone, " +
                        "so your rich chats live here too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Crash diagnosability: Garrett's device has no logcat access
            // from here, so the app captures its own uncaught exceptions
            // (see CrashReporter) and surfaces the last one here.
            val lastCrash = remember { CrashReporter.lastCrashSummary(context) }
            // RCS transport state, refreshed on a ticker while the screen is
            // visible — the sync-timeout debug round's diagnosability core.
            var transportState by remember { mutableStateOf(viewModel.transportDiagnostics()) }
            var notifState by remember { mutableStateOf(viewModel.notificationDiagnostics()) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    transportState = viewModel.transportDiagnostics()
                    notifState = viewModel.notificationDiagnostics()
                }
            }
            SettingsCard(title = "Diagnostics", icon = Icons.Filled.Warning) {
                SettingsRow(
                    title = "RCS transport",
                    subtitle = transportState,
                    actionLabel = "Refresh",
                    onAction = { transportState = viewModel.transportDiagnostics() },
                )
                SettingsRow(
                    title = "Notifications",
                    subtitle = notifState,
                    actionLabel = "Refresh",
                    onAction = { notifState = viewModel.notificationDiagnostics() },
                )
                if (lastCrash != null) {
                    var dismissed by remember { mutableStateOf(false) }
                    if (!dismissed) {
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

            Spacer(Modifier.height(8.dp))
            Text(
                "Relay 0.1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Inbox categories: the music-app-style pill bar's backing store. Add,
 * rename, delete, and REORDER categories; tap a category to assign chats
 * to it (one category per chat — assigning elsewhere moves it). "All" is
 * implicit and always shows every conversation.
 */
@Composable
private fun CategoriesCard(viewModel: SettingsViewModel) {
    val categories by viewModel.categories.collectAsState()
    val assignments by viewModel.assignments.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatCategory?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New category") },
            text = {
                androidx.compose.material3.TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("e.g. Work") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.addCategory(name)
                        showAddDialog = false
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename category") },
            text = {
                androidx.compose.material3.TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.renameCategory(target.id, name)
                        renameTarget = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    SettingsCard(title = "Categories", icon = Icons.Filled.Send) {
        Text(
            "Organize chats into groups shown in the inbox pill bar. " +
                "Tap a category to choose which chats belong to it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Built-in Unknown tab (Garrett 2026-10-19): automatic, always last
        // in the pill bar. While on, chats from bare unsaved numbers
        // (campaign spam, short codes) move out of All into their own tab.
        val unknownEnabled by viewModel.unknownEnabled.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Unknown senders", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Auto-group chats from unsaved numbers into their own tab",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = unknownEnabled,
                onCheckedChange = viewModel::setUnknownEnabled,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (categories.isEmpty()) {
            Text(
                "No categories yet — the inbox shows everything under All.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        categories.forEachIndexed { index, category ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                expandedId = if (expandedId == category.id) null else category.id
                            }
                            .padding(vertical = 12.dp),
                    )
                    // Reorder
                    IconButton(
                        onClick = { viewModel.moveCategory(category.id, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.moveCategory(category.id, 1) },
                        enabled = index < categories.size - 1,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { renameTarget = category },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deleteCategory(category.id) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Assignment: which chats belong to this category.
                if (expandedId == category.id) {
                    val memberCount = conversations.count { conv ->
                        assignments[CategoryStore.conversationKey(
                            conv.rcsConversationId, conv.threadId,
                        )] == category.id
                    }
                    Text(
                        "$memberCount chat${if (memberCount == 1) "" else "s"} in ${category.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // Unknown-number threads are auto-classified while the
                    // Unknown tab is on — they can't be manually
                    // recategorized, so they're hidden from the picker.
                    val assignable = if (unknownEnabled) {
                        conversations.filterNot { it.isUnknownNumber() }
                    } else conversations
                    assignable.take(200).forEach { conv ->
                        val key = CategoryStore.conversationKey(
                            conv.rcsConversationId, conv.threadId,
                        )
                        val checked = assignments[key] == category.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.assignChat(
                                        conv, if (checked) null else category.id,
                                    )
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    viewModel.assignChat(
                                        conv, if (isChecked) category.id else null,
                                    )
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                conv.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        androidx.compose.material3.TextButton(onClick = { showAddDialog = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add category")
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ThemeModeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    mode.label(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

