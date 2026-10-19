package com.garrietta.relay.ui

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.expressiveShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * New-conversation sheet with LIVE contact search: as Garrett types a name
 * or a number, device contacts (READ_CONTACTS) filter in real time; tapping
 * a match starts the chat. A raw typed number still works — the "Text …"
 * row starts a chat on the digits directly.
 *
 * Layout: a near-full-screen bottom sheet (~92% height) with the search
 * field pinned near the top and the results list filling everything below
 * it — full-height and scrollable BEHIND the keyboard (never compressed
 * into the space above it), so matches are visible while typing. The Cancel
 * button floats pinned above the keyboard via imePadding and stays
 * reachable; the list's bottom content padding clears it so no result is
 * ever clipped underneath.
 *
 * Keyboard bug fix: the field is KeyboardType.Text (a TEXT keyboard), not
 * Phone — the dial-pad was wrong for name search.
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
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
        // Raise the keyboard once the sheet exists (it enters with a
        // slide-up, so focus waits for the composition, not the animation).
        LaunchedEffect(visible) {
            if (visible) focusRequester.requestFocus()
        }
        // Scrim fade — the sheet itself slides up (see below).
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = RelaySprings.bouncy(),
            label = "dialogAlpha",
        )
        // LOCKED sheet height (Garrett 2026-10-19): captured ONCE per open as
        // an absolute Dp — never a fraction re-resolved against live
        // constraints, so no animation or recomposition can perturb it and
        // it can never accumulate across opens (the caller also keys each
        // open fresh).
        val sheetHeight = remember {
            (LocalConfiguration.current.screenHeightDp * 0.92f).dp
        }

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
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Music-player sheet entrance (Garrett 2026-10-19): the sheet
            // slides UP from the bottom with the bouncy spring — no more
            // scale-in. The screen-level nav transition is a plain fade for
            // this destination (see MainActivity) so the two never fight.
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = RelaySprings.bouncy(),
                ) { it } + fadeIn(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter),
                label = "newChatSheet",
            ) {
            Surface(
                // NO imePadding here: the sheet is full-height and the result
                // list extends BEHIND the keyboard (Garrett's directive —
                // "the list should simply go behind the keyboard"), not
                // compressed into the cramped space above it. Only the Cancel
                // button carries imePadding, pinned above the keyboard.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    // Swallow taps so they don't fall through to the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        // Compact header — the search field sits near the top so
                        // results are visible while typing.
                        Text(
                            "New message",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Name or number") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { keyboardController?.hide() },
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                        Spacer(Modifier.height(8.dp))
                        // Results list: full-height, scrolls BEHIND the
                        // keyboard. Bottom content padding clears the
                        // floating Cancel button so the last result is never
                        // clipped underneath it.
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 88.dp),
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
                    }
                    // Cancel floats above the keyboard (imePadding), pinned to
                    // the bottom-end — reachable without compressing the list.
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .imePadding()
                            .padding(end = 24.dp, bottom = 16.dp),
                    ) {
                        // Tonal button in a cookie shape — dialog actions are
                        // part of the shape family too, not default pills.
                        // The SHAPE itself is enlarged to fit the label (min
                        // size) — no content padding; the text was overflowing
                        // the small diamond.
                        FilledTonalButton(
                            onClick = onDismiss,
                            shape = expressiveShape("Cookie4"),
                            modifier = Modifier.sizeIn(minWidth = 128.dp, minHeight = 56.dp),
                        ) { Text("Cancel") }
                    }
                }
            }
            } // AnimatedVisibility (sheet slide-up)
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
            shapeKey = match.number,
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
