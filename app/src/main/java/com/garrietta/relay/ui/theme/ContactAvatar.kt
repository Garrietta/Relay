package com.garrietta.relay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

/**
 * Shared contact avatar: the contact's profile photo (content:// PHOTO_URI,
 * loaded with Coil) clipped to an expressive shape, with the initial letter
 * composed underneath so it shows through whenever there's no photo or the
 * load fails.
 *
 * [shapeKey]: when provided, the avatar takes its shape from the per-session
 * [AvatarShapeAssigner] rotation (a different M3E shape per contact,
 * reshuffled every app startup). When null, [shape] is used as-is.
 *
 * UNIFORM HEIGHT: [size] is the avatar's height. With a rotation shape, the
 * container widths itself as size * shape.aspectRatio so EVERY shape renders
 * at the same visual height with its original proportions (a skinny shape
 * stays skinny, just at the common height); the photo center-crops to fit.
 * Explicit [shape] avatars keep a square box.
 *
 * When a photo IS present, it also feeds the per-contact Palette theming
 * (see ChatViewModel.loadContactTheme) — photo and color identity stay one.
 */
@Composable
fun ContactAvatar(
    photoUri: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shape: Shape = WavyBlobShape,
    shapeKey: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val scheme = MaterialTheme.colorScheme
    // Expressive rotation wins over the explicit shape when a key is given.
    val namedShape = if (shapeKey != null) {
        remember(shapeKey) { AvatarShapeAssigner.namedShapeFor(shapeKey) }
    } else {
        null
    }
    val resolvedShape = namedShape?.shape ?: shape
    // Same height for every shape; width follows the shape's nature.
    val aspect = namedShape?.aspectRatio ?: 1f
    // Deterministic per-contact fallback colors from the wallpaper scheme.
    val (bg, fg) = remember(name, scheme) {
        val palette = listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
        )
        palette[(name.hashCode().and(Int.MAX_VALUE)) % palette.size]
    }
    Box(
        modifier = modifier
            .height(size)
            .width(size * aspect)
            .clip(resolvedShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = textStyle,
            color = fg,
        )
        if (photoUri != null) {
            // Expressive loading state: the seven-shape loop while the
            // photo resolves, instead of a spinner or a blank blob.
            var imgState by remember(photoUri) {
                mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
            }
            AsyncImage(
                model = photoUri,
                contentDescription = "Profile photo for $name",
                onState = { imgState = it },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(resolvedShape),
                contentScale = ContentScale.Crop,
            )
            if (imgState is AsyncImagePainter.State.Loading) {
                SevenShapeLoadingIndicator(
                    diameter = size * 0.45f,
                    color = fg,
                )
            }
        }
    }
}

