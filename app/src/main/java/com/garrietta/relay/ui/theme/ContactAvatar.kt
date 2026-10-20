package com.garrietta.relay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
 *
 * When there is NO photo, the initial sits on the contact's assigned
 * Material 3 Expressive palette ([ContactPalettes] — Garrett 2026-10-20):
 * one palette per contact, keyed by the same identity as the shape, the
 * same on every surface. [paletteIndex] lets a group cluster override
 * the raw assignment when two members would collide exactly.
 *
 * STANDING RULE (1:1 square): every avatar shape is scaled to fit inside a
 * 1:1 square bounding box ([size] x [size]), so all avatar shapes have
 * consistent sizing regardless of the polygon's aspect ratio. The photo
 * center-crops to fill the shape; the shape itself is never distorted
 * (uniform scale only).
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
    /** Cluster-resolved palette override (see ContactPalettes). */
    paletteIndex: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // Expressive rotation wins over the explicit shape when a key is given.
    val namedShape = if (shapeKey != null) {
        remember(shapeKey) { AvatarShapeAssigner.namedShapeFor(shapeKey) }
    } else {
        null
    }
    val resolvedShape = namedShape?.shape ?: shape
    // 1:1 SQUARE RULE: the shape is uniformly scaled to fit inside a
    // size×size square — wide shapes shrink vertically, tall shapes shrink
    // horizontally, square shapes fill it. HARD RATIO LOCK holds: uniform
    // scale only, exact designed aspect, never distorted.
    val aspect = namedShape?.aspectRatio ?: 1f
    val shapeWidth: Dp
    val shapeHeight: Dp
    if (aspect >= 1f) {
        shapeWidth = size
        shapeHeight = size / aspect
    } else {
        shapeWidth = size * aspect
        shapeHeight = size
    }
    // OPTICAL CENTERING (Garrett 2026-10-19): the initial letter (and
    // the loading indicator) sit at the shape's measured area centroid
    // ([NamedShape.opticalAnchor]), not the bounding-box center —
    // asymmetric shapes (triangle, pentagon, fan...) carry their
    // visual mass off-center, so box-centered letters looked lost in
    // them. The photo is NOT offset: it fills the clip as before.
    // Shapes outside the vocabulary keep box centering (offset 0).
    val anchorX = namedShape?.let { shapeWidth * (it.opticalAnchor.first - 0.5f) } ?: 0.dp
    val anchorY = namedShape?.let { shapeHeight * (it.opticalAnchor.second - 0.5f) } ?: 0.dp
    // Photo-less identity (Garrett 2026-10-20): the contact's assigned
    // expressive palette — keyed by the SAME identity that keys the
    // shape (the address-based [shapeKey], the display name only when
    // no key was passed), resolved once here so every surface agrees.
    // The pair is pre-verified WCAG AA in ContactPalettes and passed
    // through the codebase's ensureContrast guard again, so the
    // initial is legible on its ground in either scheme.
    val palette = remember(paletteIndex, shapeKey, name) {
        paletteIndex?.let { ContactPalettes.paletteAt(it) }
            ?: ContactPalettes.paletteFor(shapeKey ?: name)
    }
    val darkTheme = scheme.background.luminance() < 0.5f
    val bg = palette.background(darkTheme)
    val fg = remember(palette, darkTheme) {
        Color(ensureContrast(palette.onBackground(darkTheme).toArgb(), bg.toArgb()))
    }
    // Outer box = the 1:1 square slot; inner box = the shape at its
    // aspect-correct fit-inside-square size, centered. The caller's modifier
    // (shared-element, press scale, cluster offsets, rotations) lands on
    // the square slot.
    Box(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(shapeWidth)
                .height(shapeHeight)
                .clip(resolvedShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = textStyle,
                color = fg,
                modifier = Modifier.offset(x = anchorX, y = anchorY),
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
                        modifier = Modifier.offset(x = anchorX, y = anchorY),
                        diameter = size * 0.45f,
                        color = fg,
                    )
                }
            }
        }
    }
}


