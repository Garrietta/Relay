package com.garrietta.relay.ui

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * New-conversation dialog.
 *
 * Flash fix (round 2): the root cause was the dialog *window*, not the scrim.
 * Theme.Relay inherits android:Theme.Material (light-only), so the platform
 * dialog theme painted a light panel background for the first frame even in
 * dark mode. Fixed at two levels:
 *  1. Theme level: Theme.Relay.Dialog (android:dialogTheme) has a transparent
 *     windowBackground — no unthemed frame, ever. The platform dim behind the
 *     window is the scrim (plain black-translucent, correct in both modes).
 *     Note: this Compose version's DialogProperties has no scrimColor
 *     parameter, so the platform dim is the only scrim mechanism — which is
 *     why the theme-level transparent background matters so much.
 *  2. Compose level: plain Dialog (not AlertDialog) plus a SideEffect that
 *     forces the window background transparent, as belt-and-suspenders for
 *     any Compose version that doesn't resolve android:dialogTheme.
 * Surface is surfaceContainerHigh — theme-correct in both modes.
 */
@Composable
fun NewConversationDialog(
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setBackgroundDrawable(
                ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }

        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        val scale by animateFloatAsState(
            targetValue = if (visible) 1f else 0.9f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "dialogScale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(180),
            label = "dialogAlpha",
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                .clip(RoundedCornerShape(28.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            ) {
                Text(
                    "New message",
                    style = MaterialTheme.typography.headlineSmall,
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
