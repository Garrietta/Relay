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
import androidx.compose.ui.draw.clipToBounds
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
 * UNIFORM HEIGHT + HARD RATIO LOCK: [size] is the avatar's height. With a
 * rotation shape, the avatar is drawn at the shape's full natural size
 * (height × height×aspectRatio, uniform scale — the shape's exact designed
 * ratio, never distorted), center-cropped to at most 1.25x the height so
 * wide shapes can't become lozenges; the photo center-crops to fit.
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
    // Same height for every shape; width follows the shape's nature —
    // HARD RATIO LOCK: the shape is always drawn at its full natural size
    // (uniform scale, exact designed aspect — never distorted), and any
    // width beyond 1.25x the height is center-CROPPED, so genuinely wide
    // shapes (Pill, Clamshell) can't become lozenges.
    val aspect = namedShape?.aspectRatio ?: 1f
    val fullWidth = size * aspect
    val visibleWidth = minOf(fullWidth, size * MAX_AVATAR_ASPECT)
    // Deterministic per-contact fallback colors from the wallpaper scheme.
    val (bg, fg) = remember(name, scheme) {
        val palette = listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
        )
        palette[(name.hashCode().and(Int.MAX_VALUE)) % palette.size]
    }
    // Outer box = the visible slot (cropped to the max width); inner box =
    // the shape's full natural size. The caller's modifier (shared-element,
    // press scale, cluster offsets) lands on the visible slot.
    Box(
        modifier = modifier
            .height(size)
            .width(visibleWidth)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(size)
                .width(fullWidth)
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
}

/** Avatars are never wider than 1.25x their height — wider shapes center-crop. */
private const val MAX_AVATAR_ASPECT = 1.25f

