package com.garrietta.relay.ui

import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.expressiveShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * New-conversation search popup with LIVE contact search: as Garrett
 * types a name or a number, device contacts (READ_CONTACTS) filter in
 * real time; tapping a match starts the chat. A raw typed number still
 * works — the "Text …" row starts a chat on the digits directly.
 *
 * PRESENTATION (Garrett 2026-10-20, replacing the bottom sheet): a
 * floating rounded card anchored at the TOP CENTER of the screen, like
 * Samsung's app search — the search field is pinned at the card's top,
 * results flow beneath it inside the card, and the card's bottom edge
 * ends JUST ABOVE the keyboard. It enters as a bouncy popup (scale from
 * the top-center + fade + a short drop, [RelaySprings]).
 *
 * GROWTH BUG — ROOT CAUSE (why #119/#121 didn't hold): the old UI was a
 * platform Dialog, i.e. a SECOND window with wrap-content height, and
 * its height was `0.92 × LocalConfiguration.screenHeightDp`. Inside a
 * Dialog, `LocalConfiguration` reports the DIALOG window's size — and
 * a dialog window hosting a focused text field is resized by the
 * system as the IME shows/hides (adjustResize). The "locked" height
 * was therefore re-derived from a moving input on every keyboard
 * settle: first layout measured the pre-IME dialog window (sheet opens
 * short), then the IME finished opening, the dialog window reached
 * its resized height, `configuration` changed identity, and the sheet
 * jumped taller — the growth after the first press. The keyboard
 * stutter was the same loop (IME resize → recomposition → imePadding
 * Cancel row shifts → IME re-evaluates), compounded by a fillMaxSize
 * scrim inside a wrap-content window (window sizes to content while
 * content sizes to window).
 *
 * THE FIX IS STRUCTURAL, not another lock: there is no second window.
 * This composable renders as an overlay in the ACTIVITY's composition
 * (both call sites compose it at full-window level). The activity is
 * edge-to-edge and never resizes for the IME — the keyboard arrives
 * only as [WindowInsets.ime]. Card geometry is a pure function of
 * (fixed window size, status-bar inset, IME inset): the card fills
 * the region between the top inset and the IME top, via
 * [windowInsetsPadding]. Its height cannot be influenced by content,
 * by a transient window's configuration, or by remembered sheet
 * state; it can never exceed the space above the keyboard, and the
 * search field at the card's top cannot move at all.
 *
 * Dismissal runs the exit animation first, then invokes the caller's
 * callback (start/dismiss are delayed ~one exit duration), so the
 * popup never vanishes mid-gesture. The keyboard is hidden as the
 * exit starts — it belongs to the activity window now, so it must be
 * dismissed explicitly or it would linger over the chat.
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

    // Enter/exit state: the popup animates itself in on composition and
    // out before any callback fires. `pendingExit` doubles as the
    // re-entry guard (double-taps can't queue two actions).
    var shown by remember { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(shown) {
        if (shown) focusRequester.requestFocus()
    }
    LaunchedEffect(pendingExit) {
        val action = pendingExit ?: return@LaunchedEffect
        delay(210)
        action()
    }
    fun requestExit(action: () -> Unit) {
        if (pendingExit != null) return
        keyboardController?.hide()
        shown = false
        pendingExit = action
    }
    BackHandler(enabled = pendingExit == null) { requestExit(onDismiss) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim: theme-aware (never a hardcoded gray), covers the whole
        // window, dismisses on tap.
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            label = "newChatScrim",
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { requestExit(onDismiss) },
                    ),
            )
        }
        // Card region: the window minus the status-bar inset (top) and
        // minus the IME/navigation-bar inset (bottom) — the card's
        // bottom edge parks just above the keyboard, exactly like
        // Samsung's app-search card. Fixed margins on top of the insets.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars),
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // Bouncy popup entrance: grows from the top-center with the
            // expressive spring, a short drop and a fade.
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(120)) +
                    scaleIn(
                        animationSpec = RelaySprings.bouncy(),
                        initialScale = 0.92f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ) +
                    slideInVertically(
                        animationSpec = RelaySprings.bouncy(),
                    ) { -it / 10 },
                exit = fadeOut(tween(140)) +
                    scaleOut(
                        animationSpec = tween(180),
                        targetScale = 0.95f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ),
                modifier = Modifier.align(Alignment.TopCenter),
                label = "newChatPopup",
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        // Swallow taps so they don't fall through to the scrim.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        // Header: title + Cancel. Cancel lives INSIDE the
                        // card now — the card is always above the keyboard,
                        // so it is always reachable, no floating/imePadding
                        // positioning that can dance with IME frames.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "New message",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            // Tonal button in a cookie shape — dialog actions
                            // are part of the shape family too, not default
                            // pills. The SHAPE itself is enlarged to fit the
                            // label (min size) — no content padding.
                            FilledTonalButton(
                                onClick = { requestExit(onDismiss) },
                                shape = expressiveShape("Cookie4"),
                                modifier = Modifier.sizeIn(minWidth = 104.dp, minHeight = 48.dp),
                            ) { Text("Cancel") }
                        }
                        Spacer(Modifier.height(12.dp))
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
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // Results: fill the rest of the card (whose height
                        // comes from the insets, never from this content).
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                        ) {
                            if (showRawNumberRow) {
                                item(key = "raw:$queryDigits") {
                                    RawNumberRow(
                                        number = queryDigits,
                                        onClick = { requestExit { onStart(queryDigits) } },
                                    )
                                }
                            }
                            items(matches, key = { it.number }) { match ->
                                ContactMatchRow(
                                    match = match,
                                    onClick = { requestExit { onStart(match.number) } },
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
