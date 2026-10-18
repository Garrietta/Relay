package com.garrietta.relay.ui

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * New-conversation dialog — M3 Expressive edition.
 *
 * Issue 2 fix: the dialog window's decor background defaults to the theme's
 * windowBackground (white in light theme), which flashes for a frame before
 * the Compose content draws. We set the window background to transparent and
 * drive the scrim + surface entirely from the M3 theme — zero white frame.
 *
 * Issue 3: bubbly Cookie9Sided surface via MaterialShapes.toShape(), with a
 * springy scale+fade entrance.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewConversationDialog(
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Kill the white flash: transparent decor, theme-driven scrim/surface.
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
                stiffness = Spring.StiffnessMediumLow,
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
                .clip(MaterialShapes.Cookie9Sided.toShape()),
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
                    shape = MaterialShapes.Puffy.toShape(),
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
