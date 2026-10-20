package com.garrietta.relay.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.garrietta.relay.RelayApp
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.isUnknownSenderGhost
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatTheme
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.theme.ChatBackgroundOptions
import com.garrietta.relay.ui.theme.ChatColorwayOptions
import com.garrietta.relay.ui.theme.ClusterMember
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.GroupAvatarCluster
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.chatBackgroundBrush
import com.garrietta.relay.ui.theme.chatBubblePair
import com.garrietta.relay.ui.theme.expressiveShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UrlRegex = Regex("""https?://[^\s<>"']+""")

/**
 * Conversation / contact details (Garrett 2026-10-19) — an M3 Expressive
 * take on the Google Messages details structure from his reference video:
 * a collapsing photo header (the photo SHRINKS as you scroll — his favorite
 * transition), name + number, Call / Video / Contact info / Search actions
 * wired to the DEFAULT apps via intents (never hardcoded packages), then
 * Images, Videos, Links, members (groups), and notification settings.
 *
 * Modes: [focusAddress] set = person mode (that participant featured);
 * null on a 1:1 = the peer; null on a group = conversation mode with the
 * member list. Tapping a member drills into person mode for them.
 *
 * His "i'd really only need this section" steers the scope: the top
 * (photo/info/actions) + media are the core — no bloat, no dead toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailsScreen(
    viewModel: ChatViewModel,
    displayName: String,
    recipient: String,
    threadId: Long,
    transportLabel: String = "SMS",
    rcsConversationId: String? = null,
    /**
     * The inbox row's group classification (Garrett 2026-10-20): a known
     * group stays in conversation (member-list) mode even if this
     * screen's participant list loads thinner than the row's — the
     * surfaces must never disagree ("No Dad").
     */
    knownGroup: Boolean = false,
    focusAddress: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // True per-chat mute (Garrett 2026-10-20): stored in RelayApp's
    // MuteStore, keyed exactly like category assignments; while muted
    // the notification pipeline posts NOTHING for this conversation.
    val muteStore = remember { (context.applicationContext as RelayApp).muteStore }
    val mutedKeys by muteStore.mutedKeys.collectAsState()
    val muteKey = remember(rcsConversationId, threadId) {
        CategoryStore.conversationKey(rcsConversationId, threadId)
    }
    val muted = muteKey in mutedKeys
    // Per-chat theme (Garrett 2026-10-20, feature queue 3/7): the
    // "chat theme" row the #119 details page skipped is real now — it
    // never needed a backend, the theme is local presentation stored in
    // RelayApp's ChatThemeStore under the SAME conversation key as the
    // mute above. Picked here, applied in ChatScreen only.
    val themeStore = remember { (context.applicationContext as RelayApp).chatThemeStore }
    val chatThemes by themeStore.themes.collectAsState()
    val chatTheme = chatThemes[muteKey]
    // Swatch previews resolve for the mode the details page itself is
    // in (same background-luminance inference the chat scheme uses).
    val themeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val others by viewModel.others.collectAsState()
    val participants by viewModel.participants.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val photoUri by viewModel.photoUri.collectAsState()
    val liveTransportLabel by viewModel.transportLabel.collectAsState()

    // UNKNOWN_SENDER! ghosts never render (display layer only).
    val visibleOthers = remember(others) { others.filterNot { it.isUnknownSenderGhost() } }
    // Drill-down: tapping a group member features them; back returns to
    // the group view.
    var personAddress by remember(focusAddress) { mutableStateOf(focusAddress) }
    val featured: Participant? = remember(personAddress, visibleOthers, participants, knownGroup) {
        val addr = personAddress
        when {
            addr != null -> viewModel.senderFor(addr)
                ?: (participants + visibleOthers).firstOrNull { it.address == addr }
            // Known group, conversation mode: never feature a lone
            // participant just because this screen's list came back
            // thinner than the inbox row's (Garrett 2026-10-20).
            knownGroup -> null
            visibleOthers.size <= 1 ->
                visibleOthers.firstOrNull() ?: participants.firstOrNull()
            else -> null
        }
    }
    // Group mode when there really are multiple others — or when the
    // inbox row classified the conversation as a group (conversation
    // mode only; a member drill-down is person mode). While the
    // participant list is still loading we stay in person mode (never a
    // flash of an empty "group" view).
    val isGroup = featured == null &&
        (visibleOthers.size > 1 || (knownGroup && personAddress == null))

    val personName = featured?.let {
        if (it.isUnknownSenderGhost()) it.address
        else it.displayName.ifBlank { it.address }
    } ?: displayName
    val personNumber = featured?.address?.ifBlank { recipient } ?: recipient
    val personPhoto = featured?.photoUri ?: photoUri
    val groupTitle = displayName
    val subtitle = if (isGroup) {
        "${visibleOthers.size} members · $liveTransportLabel"
    } else {
        personNumber
    }

    // Media + links derived from the loaded messages.
    val imageMedia = remember(messages) {
        messages.mapNotNull { it.media }
            .filter { it.isImage }
            .distinctBy { it.mediaId }
    }
    val videoMedia = remember(messages) {
        messages.mapNotNull { it.media }
            .filter { it.mimeType.startsWith("video/") }
            .distinctBy { it.mediaId }
    }
    val links = remember(messages) {
        messages.flatMap { msg ->
            UrlRegex.findAll(msg.body).map { it.value.trimEnd('.', ',', ')', ';') }
        }.distinct()
    }

    // Decrypted media bytes, shared between thumbnails and the fullscreen
    // viewer so nothing downloads twice.
    val mediaCache = remember { mutableStateMapOf<String, ByteArray>() }
    var zoomedId by remember { mutableStateOf<String?>(null) }

    // In-conversation search (the Search action button).
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember(searchQuery, messages) {
        if (searchQuery.isBlank()) emptyList()
        else messages.filter { it.body.contains(searchQuery, ignoreCase = true) }
    }

    // Collapsing photo header: scroll fraction drives the photo size
    // DIRECTLY — the scroll itself is the animation (the #102 lesson: never
    // retarget a spring on every scroll frame).
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset /
                with(density) { 150.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    val photoSize = lerp(124.dp, 58.dp, collapse)

    val detailsBack = {
        if (focusAddress == null && personAddress != null) personAddress = null
        else onBack()
    }

    // Bouncy slide-up entrance, like the new-chat sheet.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        AnimatedVisibility(
            visible = entered,
            enter = slideInVertically(RelaySprings.bouncy()) { it } +
                fadeIn(RelaySprings.bouncy()),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: back + the name fading in as the photo collapses.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = detailsBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = if (isGroup) groupTitle else personName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { alpha = collapse },
                    )
                }
                // Collapsing header: photo shrinks with scroll (his favorite
                // transition), name + number below.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isGroup) {
                        GroupAvatarCluster(
                            members = visibleOthers.map {
                                ClusterMember(
                                    photoUri = it.photoUri,
                                    name = it.displayName.ifBlank { it.address },
                                    address = it.address,
                                )
                            },
                            conversationKey = rcsConversationId ?: "tel:$threadId",
                            boxSize = photoSize,
                        )
                    } else {
                        ContactAvatar(
                            photoUri = personPhoto,
                            name = personName,
                            size = photoSize,
                            shapeKey = personNumber.ifBlank { recipient },
                            textStyle = MaterialTheme.typography.displaySmall,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (isGroup) groupTitle else personName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The four actions (person mode only — his core ask).
                if (!isGroup && personNumber.isNotBlank()) {
                    DetailsActionRow(
                        onCall = { dialNumber(context, personNumber) },
                        onVideo = { videoCallNumber(context, personNumber) },
                        onContactInfo = { openSystemContact(context, personNumber) },
                        onSearch = {
                            searchOpen = !searchOpen
                            if (!searchOpen) searchQuery = ""
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                // In-conversation search field.
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn(RelaySprings.gentle()) +
                        expandVertically(RelaySprings.gentle()),
                    exit = fadeOut(RelaySprings.gentle()) +
                        shrinkVertically(RelaySprings.gentle()),
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search this conversation",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                // Content sections.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (searchOpen) {
                        if (searchQuery.isBlank()) {
                            item(key = "search-hint") {
                                Text(
                                    "Type to search messages in this conversation.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                        } else if (searchResults.isEmpty()) {
                            item(key = "search-empty") {
                                Text(
                                    "No messages match \"$searchQuery\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                        } else {
                            item(key = "search-count") {
                                Text(
                                    "${searchResults.size} matches",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                )
                            }
                            items(searchResults, key = { it.id }) { msg ->
                                SearchResultRow(
                                    body = msg.body,
                                    query = searchQuery,
                                    isOutgoing = msg.isOutgoing,
                                    senderName = if (!msg.isOutgoing && isGroup)
                                        viewModel.senderFor(msg.address)?.displayName else null,
                                    date = msg.date,
                                )
                            }
                        }
                    } else {
                        if (imageMedia.isNotEmpty()) {
                            item(key = "images") {
                                DetailsSectionCard(title = "Images", count = imageMedia.size) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                    ) {
                                        items(imageMedia, key = { it.mediaId }) { att ->
                                            DetailsImageThumb(
                                                media = att,
                                                cached = mediaCache[att.mediaId],
                                                onLoaded = { mediaCache[att.mediaId] = it },
                                                loadMedia = { viewModel.mediaBytes(att) },
                                                onClick = { zoomedId = att.mediaId },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (videoMedia.isNotEmpty()) {
                            item(key = "videos") {
                                DetailsSectionCard(title = "Videos", count = videoMedia.size) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        videoMedia.forEach { att ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    text = "Video · ${formatBytes(att.size)}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (links.isNotEmpty()) {
                            item(key = "links") {
                                DetailsSectionCard(title = "Links", count = links.size) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        links.take(12).forEach { url ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = { openLink(context, url) },
                                                    )
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Link,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    text = url,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (isGroup && visibleOthers.isNotEmpty()) {
                            item(key = "members") {
                                DetailsSectionCard(title = "Members", count = visibleOthers.size) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        visibleOthers.forEach { member ->
                                            val memberName =
                                                member.displayName.ifBlank { member.address }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = { personAddress = member.address },
                                                    )
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                ContactAvatar(
                                                    photoUri = member.photoUri,
                                                    name = memberName,
                                                    size = 44.dp,
                                                    shapeKey = member.address,
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = memberName,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = member.address,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "theme") {
                            DetailsSectionCard(title = "Chat theme") {
                                ChatThemePicker(
                                    theme = chatTheme,
                                    darkTheme = themeDark,
                                    onBackground = { id ->
                                        themeStore.setBackground(muteKey, id)
                                    },
                                    onColorway = { id ->
                                        themeStore.setColorway(muteKey, id)
                                    },
                                    onReset = { themeStore.reset(muteKey) },
                                )
                            }
                        }
                        item(key = "options") {
                            DetailsSectionCard(title = "Options") {
                              Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { openNotificationSettings(context) },
                                        )
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Notifications,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Notifications",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "System settings",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // True per-chat mute (Garrett 2026-10-20):
                                // while on, Relay posts NO notification at
                                // all for this conversation — no heads-up, no
                                // sound — while the inbox updates normally.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { muteStore.setMuted(muteKey, !muted) },
                                        )
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (muted) Icons.Filled.NotificationsOff
                                        else Icons.Filled.Notifications,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Mute notifications",
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = if (muted) {
                                                "Muted — nothing from this chat posts"
                                            } else {
                                                "Off — this chat can notify"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = muted,
                                        onCheckedChange = { muteStore.setMuted(muteKey, it) },
                                    )
                                }
                              }
                            }
                        }
                    }
                }
            }
        }
    }

    // Fullscreen image viewer. Thumbnail-only bytes (no server copy) are
    // small; upscale them for display so the viewer is legible instead of
    // tiny (Garrett 2026-10-19 evening). Display-only — the cache is untouched.
    val zoomedBytes = zoomedId?.let { mediaCache[it] }
    val zoomedDisplay = remember(zoomedBytes) {
        zoomedBytes?.let { upscaleSmallImageForDisplay(it) }
    }
    if (zoomedId != null && zoomedDisplay != null) {
        Dialog(
            onDismissRequest = { zoomedId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { zoomedId = null },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = zoomedDisplay,
                    contentDescription = "Shared image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * Display-only upscale for tiny-but-valid images (thumbnail-only bytes with
 * no server copy). If the decoded image's longest side is under 720px,
 * scales it up so the fullscreen viewer is legible. Never downscales;
 * returns the original bytes when they can't be decoded.
 */
private fun upscaleSmallImageForDisplay(bytes: ByteArray): ByteArray {
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0 || longest >= 720) return bytes
        val scale = 720f / longest
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return bytes
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        scaled.recycle()
        val upscaled = out.toByteArray()
        return if (upscaled.isEmpty()) bytes else upscaled
    } catch (_: Exception) {
        bytes
    }
}

/** The four actions, each in its own expressive shape — his core ask. */
@Composable
private fun DetailsActionRow(
    onCall: () -> Unit,
    onVideo: () -> Unit,
    onContactInfo: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        DetailsActionButton(
            icon = Icons.Filled.Call,
            label = "Call",
            shapeName = "Cookie7",
            onClick = onCall,
        )
        DetailsActionButton(
            icon = Icons.Filled.Videocam,
            label = "Video",
            shapeName = "VerySunny",
            onClick = onVideo,
        )
        DetailsActionButton(
            icon = Icons.Filled.Person,
            label = "Contact info",
            shapeName = "Quatrefoil",
            onClick = onContactInfo,
        )
        DetailsActionButton(
            icon = Icons.Filled.Search,
            label = "Search",
            shapeName = "Sunny",
            onClick = onSearch,
        )
    }
}

@Composable
private fun DetailsActionButton(
    icon: ImageVector,
    label: String,
    shapeName: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(expressiveShape(shapeName))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Chat theme picker (Garrett 2026-10-20): background gradients + bubble
 * colorways for THIS conversation, applied on this device only — the
 * pick never leaves the phone. "Default" restores today's look per
 * half (background / bubbles are independent picks); Reset clears both.
 * In groups, a picked colorway overrides the per-sender bubble colors
 * while sender names keep their per-sender tones (see chatBubblePair).
 */
@Composable
private fun ChatThemePicker(
    theme: ChatTheme?,
    darkTheme: Boolean,
    onBackground: (String?) -> Unit,
    onColorway: (String?) -> Unit,
    onReset: () -> Unit,
) {
    Text(
        text = "Background",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "bg-default") {
            ThemeSwatch(
                name = "Default",
                selected = theme?.backgroundId == null,
                onClick = { onBackground(null) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
        items(ChatBackgroundOptions, key = { "bg-${it.id}" }) { option ->
            ThemeSwatch(
                name = option.name,
                selected = theme?.backgroundId == option.id,
                onClick = { onBackground(option.id) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(chatBackgroundBrush(option.seed, darkTheme)),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Bubbles",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "cw-default") {
            ThemeSwatch(
                name = "Default",
                selected = theme?.colorwayId == null,
                onClick = { onColorway(null) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
        }
        items(ChatColorwayOptions, key = { "cw-${it.id}" }) { option ->
            val pair = remember(option.id, darkTheme) {
                chatBubblePair(option.seed, darkTheme)
            }
            ThemeSwatch(
                name = option.name,
                selected = theme?.colorwayId == option.id,
                onClick = { onColorway(option.id) },
            ) {
                // Preview = the outgoing tone with the incoming tone
                // tucked into the corner: both halves of the pair.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(pair.outgoing),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp))
                            .background(pair.incoming),
                    )
                }
            }
        }
    }
    if (theme != null && !theme.isDefault) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onReset,
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Reset to default",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Applies to this chat on this device only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One theme swatch: a rounded preview tile + name; primary ring when picked. */
@Composable
private fun ThemeSwatch(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
) {
    val swatchShape = RoundedCornerShape(18.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(swatchShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    shape = swatchShape,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            preview()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Tonal rounded section card (Images / Links / Members / Options). */
@Composable
private fun DetailsSectionCard(
    title: String,
    count: Int? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (count != null) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** One image attachment thumbnail; tap opens the fullscreen viewer. */
@Composable
private fun DetailsImageThumb(
    media: RcsMediaAttachment,
    cached: ByteArray?,
    onLoaded: (ByteArray) -> Unit,
    loadMedia: suspend () -> ByteArray,
    onClick: () -> Unit,
) {
    var failed by remember(media.mediaId) { mutableStateOf(false) }
    LaunchedEffect(media.mediaId) {
        if (cached == null && !failed) {
            runCatching { loadMedia() }
                .onSuccess(onLoaded)
                .onFailure { failed = true }
        }
    }
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = cached != null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bytes = cached
        when {
            bytes != null -> AsyncImage(
                model = bytes,
                contentDescription = "Shared image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Text(
                "!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> SevenShapeLoadingIndicator(
                diameter = 40.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One in-conversation search hit. */
@Composable
private fun SearchResultRow(
    body: String,
    query: String,
    isOutgoing: Boolean,
    senderName: String?,
    date: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isOutgoing) "You" else senderName ?: "",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDetailsDate(date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatDetailsDate(millis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(millis))

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "${n / (1024 * 1024)} MB"
}

// ---------------------------------------------------------------------------
// Default-app intents. Garrett 2026-10-19: "it would just default to my
// default app for each, right." Nothing is hardcoded to a package — the
// system resolves each intent to his defaults (Samsung dialer, Meet).
// ---------------------------------------------------------------------------

/** Call: ACTION_DIAL lands in the default dialer with the number filled in. */
private fun dialNumber(context: Context, number: String) {
    if (number.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Video: ACTION_VIEW on the tel: URI resolves to the default phone/video
 * handler, where the video call is one tap. (Placing a call directly via
 * TelecomManager would dial immediately instead of landing in his app —
 * not what he asked for.)
 */
private fun videoCallNumber(context: Context, number: String) {
    if (number.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:${number.trim()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Contact info (Garrett 2026-10-19 21:54 bug fix): the old code fired
 * ACTION_VIEW on the PhoneLookup FILTER URI — a bare implicit intent any
 * app can claim, and on his phone PokeDaisy (an unrelated emulator) had
 * claimed it, so "Contact info" opened that instead of Contacts. Fixed
 * per his steer: resolve the number on OUR side to the concrete
 * ContactsContract contact and ACTION_VIEW that contact's own URI
 * (content://com.android.contacts/contacts/<id>); if the person has no
 * contact entry, fall back to the Contacts app main list
 * (ContactsContract.Contacts.CONTENT_URI). Never a filter/intent an
 * arbitrary app can intercept.
 */
private fun openSystemContact(context: Context, number: String) {
    if (number.isBlank()) return
    runCatching {
        val target = resolveContactViewUri(context, number.trim())
            ?: ContactsContract.Contacts.CONTENT_URI
        context.startActivity(
            Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Resolves a phone number to that contact's view URI, or null. */
private fun resolveContactViewUri(context: Context, number: String): Uri? = runCatching {
    val filter = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(number),
    )
    context.contentResolver.query(
        filter,
        arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.LOOKUP_KEY,
        ),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) {
            val id = c.getLong(0)
            val key = c.getString(1)
            return@runCatching ContactsContract.Contacts.getLookupUri(id, key)
                ?: Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI, id.toString(),
                )
        }
    }
    null
}.getOrNull()

/** Notifications row: straight to the system per-app notification settings. */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Links open in the default browser. */
private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
