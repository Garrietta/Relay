package com.garrietta.relay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.garrietta.relay.data.model.ThemeMode

/**
 * Relay design system — Material 3 Expressive feel on stable material3 1.4.0.
 *
 * Expressive via dynamic color, large bubbly shapes, and spring physics.
 * (material3 1.5.0-beta01's MaterialShapes/LoadingIndicator/FABMenu need
 * SDK 37 + AGP 9.1.0, not publicly available as of 2026-10; shapes are
 * hand-rolled large radii, motion uses the centralized ui.motion policy
 * adapted from m3x-motion-recipes, Apache-2.0.)
 */

// Expressive type scale: oversized editorial headlines, tight tracking,
// heavy contrast between display and body.
private val RelayTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 52.sp,
        lineHeight = 56.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)

// Expressive shape scale: soft bubbles, bold floating elements.
private val RelayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Message bubble shapes: soft all around, tight corner on the tail side. */
val outgoingBubbleShape = RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp)
val incomingBubbleShape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)

// Motion-budget note: there is intentionally no shared spring helper here.
// Springs are sanctioned only for send pop, the FAB-menu fan-out, container
// transforms, and the pill press — each call site declares its own spec so
// the budget stays visible. Everything else uses the centralized ui.motion
// tokens (standard easing + duration tokens).

@Composable
fun RelayTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** App-wide contact seed; null = wallpaper dynamic scheme. */
    seedColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val baseScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    // App-wide persistent contact color: the last-viewed contact's seed
    // tints the whole app (richer than the chat-scoped version — this is the
    // "more color" Garrett asked for). Falls back to wallpaper dynamic.
    val colorScheme = if (seedColor != null) {
        seededAppColorScheme(seedColor, darkTheme, baseScheme)
    } else {
        baseScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RelayTypography,
        shapes = RelayShapes,
        content = content,
    )
}

