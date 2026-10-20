package com.garrietta.relay.ui

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * New-conversation search popup with LIVE contact search: as Garrett
 * types a name or a number, device contacts (READ_CONTACTS) filter in
 * real time; tapping a match starts the chat. A raw typed number still
 * works — the "Text …" row starts a chat on the digits directly.
 *
 * PRESENTATION: the shared [TopSearchPopup] scaffold (Garrett
 * 2026-10-20) — a floating rounded card anchored at the TOP CENTER of
 * the screen, the search field pinned at the card's top, results
 * beneath it inside the card, the card's bottom edge parked just above
 * the keyboard, entering as a bouncy popup. The inbox conversation
 * search uses the identical scaffold, so the two search surfaces can
 * never drift apart. The growth-bug history (the old Dialog window
 * resizing with the IME) is documented in TopSearchPopup — the fix is
 * structural and lives there now.
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

    TopSearchPopup(
        title = "New message",
        query = query,
        onQueryChange = { query = it },
        placeholder = "Name or number",
        onDismiss = onDismiss,
    ) { requestExit ->
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
