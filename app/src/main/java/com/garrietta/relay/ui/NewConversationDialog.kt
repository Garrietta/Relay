package com.garrietta.relay.ui

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * New-conversation dialog with LIVE contact search: as Garrett types a name
 * or a number, device contacts (READ_CONTACTS) filter in real time; tapping
 * a match starts the chat. A raw typed number still works — the "Text …"
 * row starts a chat on the digits directly.
 *
 * Flash fix (round 3): platform-level transparency never took effect —
 * `LocalView.current`'s parent inside dialog content is a container ViewGroup,
 * not the DialogWrapper, so the `(view.parent as? DialogWindowProvider)` cast
 * silently yielded null, and Compose's DialogWrapper doesn't resolve
 * `android:dialogTheme` on this version. The light `Theme.Material.Dialog`
 * panel background therefore survived (the full-screen gray in dark mode).
 *
 * The bulletproof fix: draw the scrim INSIDE the Compose dialog content. The
 * Dialog's content is a full-screen Box painted with the theme-aware
 * `colorScheme.scrim` (black in both M3 light and dark schemes — never a
 * hardcoded gray), with the dialog Surface centered inside it. Whatever the
 * platform window does underneath is now irrelevant.
 */
@Composable
fun NewConversationDialog(
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ContactMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // Live search, debounced ~200ms; the query coroutine is cancelled on
    // every keystroke so only the latest search lands.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            matches = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(200)
        val q = query
        matches = withContext(Dispatchers.IO) { searchPhoneContacts(context, q) }
        searching = false
    }

    val queryDigits = query.filter { it.isDigit() }
    // Raw-number row: typing digits offers "Text <number>" directly, so a
    // number that isn't in contacts still starts a chat.
    val showRawNumberRow = queryDigits.length >= 3 &&
        matches.none { it.number.filter(Char::isDigit) == queryDigits }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // We handle outside-taps ourselves via the scrim Box below.
            dismissOnClickOutside = false,
        ),
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        // Bouncy dialog entrance — the unified spring language.
        val scale by animateFloatAsState(
            targetValue = if (visible) 1f else 0.92f,
            animationSpec = RelaySprings.bouncy(),
            label = "dialogScale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = RelaySprings.bouncy(),
            label = "dialogAlpha",
        )

        // In-Compose scrim: theme-aware, covers the whole window, dismisses
        // on tap. The platform window background is irrelevant behind this.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = alpha)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    // Swallow taps so they don't fall through to the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Text(
                        "New message",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Name or number") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Results list: fixed max height, scrolls when long.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    ) {
                        if (showRawNumberRow) {
                            item(key = "raw:$queryDigits") {
                                RawNumberRow(
                                    number = queryDigits,
                                    onClick = { onStart(queryDigits) },
                                )
                            }
                        }
                        items(matches, key = { it.number }) { match ->
                            ContactMatchRow(
                                match = match,
                                onClick = { onStart(match.number) },
                            )
                        }
                        if (searching) {
                            item(key = "searching") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SevenShapeLoadingIndicator(
                                        diameter = 32.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else if (query.isNotBlank() && matches.isEmpty() && !showRawNumberRow) {
                            item(key = "empty") {
                                Text(
                                    "No contacts match \"$query\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** One device contact: name, phone number, optional profile photo. */
private data class ContactMatch(
    val name: String,
    val number: String,
    val photoUri: String?,
)

/**
 * Filters the device's phone contacts by [query]: name substring (case
 * insensitive) OR digit-substring on the number. Runs on Dispatchers.IO.
 * Results deduped by normalized number, capped for list performance.
 */
private fun searchPhoneContacts(context: Context, query: String): List<ContactMatch> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val qDigits = q.filter { it.isDigit() }
    val out = linkedMapOf<String, ContactMatch>()
    try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            val nameI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (c.moveToNext() && out.size < 25) {
                val name = if (nameI >= 0) c.getString(nameI).orEmpty() else ""
                val number = if (numI >= 0) c.getString(numI).orEmpty() else ""
                if (number.isBlank()) continue
                val numDigits = number.filter { it.isDigit() }
                val nameHit = name.contains(q, ignoreCase = true)
                val numHit = qDigits.isNotEmpty() && numDigits.contains(qDigits)
                if (!nameHit && !numHit) continue
                val photo = if (photoI >= 0) c.getString(photoI) else null
                out.getOrPut(numDigits) { ContactMatch(name.ifBlank { number }, number, photo) }
            }
        }
    } catch (_: SecurityException) {
        // READ_CONTACTS not granted — the raw-number row still works.
    } catch (_: Exception) { }
    return out.values.toList()
}

@Composable
private fun ContactMatchRow(match: ContactMatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(
            photoUri = match.photoUri,
            name = match.name,
            size = 48.dp,
            textStyle = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                match.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                match.number,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RawNumberRow(number: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "#",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Text $number",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Start a new conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
