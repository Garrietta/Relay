package com.garrietta.relay.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import com.garrietta.relay.ui.motion.M3Duration
import com.garrietta.relay.ui.motion.M3Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * New-conversation dialog.
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
    var number by remember { mutableStateOf("") }

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
        // Motion budget: dialogs are high-frequency chrome, so the entrance
        // uses standard emphasized easing — springs are reserved for send,
        // the FAB menu, container transforms, and the pill.
        val scale by animateFloatAsState(
            targetValue = if (visible) 1f else 0.92f,
            animationSpec = tween(
                durationMillis = M3Duration.Medium2,
                easing = M3Easing.EmphasizedDecelerate,
            ),
            label = "dialogScale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(180),
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
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                ) {
                    Text(
                        "New message",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        placeholder = { Text("Phone number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(
                            onClick = { onStart(number.trim()) },
                            enabled = number.trim().length >= 3,
                        ) { Text("Start") }
                    }
                }
            }
        }
    }
}

