package com.garrietta.relay.ui

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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garrietta.relay.ui.motion.RelaySprings
import kotlinx.coroutines.delay

/**
 * The shared top-center search popup (Garrett 2026-10-20: the new-chat
 * popup from Build #138 became the app's search presentation, and the
 * inbox search button must open "the same popup as the new chat
 * button"). Both search surfaces — NewConversationDialog (device
 * contacts) and the inbox conversation search — render through this
 * one scaffold so the presentation can never drift apart: same card,
 * same top-center anchor, same bouncy entrance/exit, same IME-parked
 * bottom edge.
 *
 * PRESENTATION: a floating rounded card anchored at the TOP CENTER of
 * the screen, like Samsung's app search — the search field is pinned
 * at the card's top, [content] (the results) flows beneath it inside
 * the card, and the card's bottom edge ends JUST ABOVE the keyboard.
 * It enters as a bouncy popup (scale from the top-center + fade + a
 * short drop, [RelaySprings]).
 *
 * STRUCTURE (the #138 growth fix — do not regress): there is no second
 * window. This composable renders as an overlay in the ACTIVITY's
 * composition; the activity is edge-to-edge and never resizes for the
 * IME — the keyboard arrives only as [WindowInsets.ime]. Card geometry
 * is a pure function of (fixed window size, status-bar inset, IME
 * inset): the card fills the region between the top inset and the IME
 * top via [windowInsetsPadding]. Its height cannot be influenced by
 * content, by a transient window's configuration, or by remembered
 * state; it can never exceed the space above the keyboard, and the
 * search field at the card's top cannot move at all.
 *
 * EXIT DISCIPLINE: dismissal runs the exit animation first, then
 * invokes the pending callback (row action or [onDismiss]) ~one exit
 * duration later, so the popup never vanishes mid-gesture. The
 * keyboard is hidden as the exit starts — it belongs to the activity
 * window, so it must be dismissed explicitly or it would linger.
 * [dismissSignal] lets an outside control (the inbox search button,
 * which stays visible above the scrim) request the same animated exit:
 * increment it to close the popup as if Cancel was pressed.
 */
@Composable
fun TopSearchPopup(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    dismissSignal: Int = 0,
    content: @Composable ColumnScope.(requestExit: (() -> Unit) -> Unit) -> Unit,
) {
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
    // External close (e.g. the inbox search button toggled off): same
    // animated exit as Cancel. Only a CHANGE fires — the signal keeps
    // its value across popup sessions, so the value seen at composition
    // is the baseline, never a pending request.
    var lastDismissSignal by remember { mutableStateOf(dismissSignal) }
    LaunchedEffect(dismissSignal) {
        if (dismissSignal != lastDismissSignal) {
            lastDismissSignal = dismissSignal
            requestExit(onDismiss)
        }
    }
    BackHandler(enabled = pendingExit == null) { requestExit(onDismiss) }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim: theme-aware (never a hardcoded gray), covers the whole
        // window, dismisses on tap.
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            label = "searchPopupScrim",
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
                label = "searchPopup",
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
                        // card — the card is always above the keyboard, so
                        // it is always reachable, no floating/imePadding
                        // positioning that can dance with IME frames.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            // Tonal Cancel — a rounded-off rectangle with
                            // the category bar's small chip radius (10dp,
                            // the unselected middle chips), NOT a cookie and
                            // not a pill (Garrett 2026-10-20). Content-
                            // hugging: no forced min size — the button
                            // wraps the label with even, comfortable
                            // padding so it never looks cramped.
                            FilledTonalButton(
                                onClick = { requestExit(onDismiss) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(
                                    horizontal = 16.dp,
                                    vertical = 10.dp,
                                ),
                            ) { Text("Cancel") }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            placeholder = { Text(placeholder) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = keyboardType,
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
                        content { action -> requestExit(action) }
                    }
                }
            }
        }
    }
}
