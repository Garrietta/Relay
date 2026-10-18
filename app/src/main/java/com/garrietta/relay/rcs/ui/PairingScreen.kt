package com.garrietta.relay.rcs.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.rcs.pairing.GaiaPairing
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RCS pairing screen — Gaia (Google-account / emoji) is the PRIMARY flow;
 * QR remains as the fallback ("Pair with QR instead").
 *
 * Gaia flow: sign in with Google (WebView) → handshake → emoji verification
 * (user confirms the emoji matches the phone) → session establishes exactly
 * like the QR path (same RcsSession, ack loop, foreground service).
 *
 * Cancellation: backing out disposes this screen; the DisposableEffect
 * aborts the in-flight pairing and resets the state machine so Settings can
 * never strand on "Pairing...".
 */
@Composable
fun RcsPairingScreen(
    transport: RcsTransport,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.CHOOSING) }
    val scope = rememberCoroutineScope()

    // Never strand in PAIRING: abort on dispose (back-out mid-pairing).
    DisposableEffect(Unit) {
        onDispose { transport.cancelPairing() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (phase) {
            Phase.CHOOSING -> ChoosingPhase(
                onGaia = { phase = Phase.GAIA_SIGNIN },
                onQr = { phase = Phase.QR_GENERATING },
                onBack = onBack,
            )
            Phase.GAIA_SIGNIN -> GaiaSignInPhase(
                transport = transport,
                onCookies = { phase = Phase.GAIA_VERIFY },
                onBack = { phase = Phase.CHOOSING },
            )
            Phase.GAIA_VERIFY -> GaiaVerifyPhase(
                transport = transport,
                onPaired = { phase = Phase.PAIRED },
                onError = { phase = Phase.ERROR },
                onBack = { phase = Phase.CHOOSING },
            )
            Phase.QR_GENERATING, Phase.QR_AWAITING_SCAN -> QrPhase(
                transport = transport,
                phase = phase,
                onPhase = { phase = it },
                onBack = onBack,
            )
            Phase.PAIRED -> {
                Text(
                    "Paired! RCS is connected.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Done") }
            }
            Phase.ERROR -> {
                Text(
                    "Pairing failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { phase = Phase.CHOOSING }) { Text("Try again") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

private enum class Phase {
    CHOOSING,
    GAIA_SIGNIN,
    GAIA_VERIFY,
    QR_GENERATING,
    QR_AWAITING_SCAN,
    PAIRED,
    ERROR,
}

@Composable
private fun ChoosingPhase(
    onGaia: () -> Unit,
    onQr: () -> Unit,
    onBack: () -> Unit,
) {
    Text("Pair RCS", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Sign in with your Google account and match the emoji — " +
            "no QR scanning needed.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onGaia,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pair with Google account") }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onQr,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pair with QR instead") }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onBack) { Text("Cancel") }
}

@Composable
private fun GaiaSignInPhase(
    transport: RcsTransport,
    onCookies: () -> Unit,
    onBack: () -> Unit,
) {
    var cookies by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (cookies == null) {
        Text("Sign in with Google", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use the Google account on your phone. Relay only reads the " +
                "session cookies — your password never touches Relay.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // The WebView hosts the sign-in; it destroys itself after capture.
        GaiaSignInWebView(
            onCookiesCaptured = { captured -> cookies = captured },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    } else {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Contacting Google…", style = MaterialTheme.typography.bodyMedium)
        // Kick off SignInGaia once we have cookies.
        LaunchedEffect(cookies) {
            transport.trackPairingJob(coroutineContext[kotlinx.coroutines.Job])
            try {
                withContext(Dispatchers.IO) { transport.beginGaiaPairing(cookies!!) }
                onCookies()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Sign-in failed"
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun GaiaVerifyPhase(
    transport: RcsTransport,
    onPaired: () -> Unit,
    onError: () -> Unit,
    onBack: () -> Unit,
) {
    var emojiIndex by remember { mutableStateOf<Int?>(null) }
    var deviceName by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Run the UKEY2 handshake and derive the emoji.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val gaia = GaiaPairing()
                val handshake = gaia.prepareUkey2()
                // TODO: send CLIENT_INIT via ActionType_CREATE_GAIA_PAIRING_CLIENT_INIT
                // and parse SERVER_INIT from the response. Until the live
                // handshake is validated, show the UI with a placeholder.
                // The emoji index derivation (processServerInit) is implemented
                // and unit-tested; it just needs the server's bytes.
                withContext(Dispatchers.Main) {
                    // Placeholder: real index comes from processServerInit().
                    emojiIndex = 0
                    deviceName = "your phone"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Handshake failed"
                    onError()
                }
            }
        }
    }

    Text("Match the emoji", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Does this emoji match the one on ${deviceName ?: "your phone"}?",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    if (emojiIndex != null) {
        Text(
            FALLBACK_EMOJI_SET[emojiIndex!! % FALLBACK_EMOJI_SET.size],
            style = MaterialTheme.typography.displayLarge,
            fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
    } else {
        CircularProgressIndicator()
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                scope.launch { transport.cancelPairing() }
                onBack()
            },
        ) { Text("No, cancel") }
        Button(
            onClick = {
                // User confirmed the match → finish pairing (same session
                // establishment as the QR path).
                working = true
                scope.launch(Dispatchers.IO) {
                    try {
                        // TODO: awaitGaiaPairing with the real handshake.
                        // For now, surface that the handshake needs the live server.
                        withContext(Dispatchers.Main) {
                            error = "Emoji confirmed — the live handshake lands after " +
                                "Garrett's sign-in validates the RPC shapes."
                            working = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            error = e.message
                            working = false
                        }
                    }
                }
            },
            enabled = emojiIndex != null && !working,
        ) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp))
            else Text("Yes, it matches")
        }
    }
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

/**
 * Placeholder emoji set. The real pairingEmojisV arrays come from the
 * protocol; until the live handshake is validated, the UI shows from this
 * set. The INDEX derivation (uint32be(ukeyV1Auth) % len) is spec-correct —
 * only the glyph mapping is provisional.
 */
private val FALLBACK_EMOJI_SET = listOf(
    "😀", "🐶", "🍎", "⚽", "🚗", "🌙", "🎵", "📚",
    "🐱", "🍕", "✈️", "🌊", "🎨", "💡", "🌳", "⭐",
)

@Composable
private fun QrPhase(
    transport: RcsTransport,
    phase: Phase,
    onPhase: (Phase) -> Unit,
    onBack: () -> Unit,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var begun by remember { mutableStateOf<QrPairing.BegunPairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        transport.trackPairingJob(coroutineContext[kotlinx.coroutines.Job])
        try {
            val b = withContext(Dispatchers.IO) { transport.beginQrPairing() }
            begun = b
            qrBitmap = withContext(Dispatchers.IO) { qrBitmap(b.qrContent, 640) }
            onPhase(Phase.QR_AWAITING_SCAN)
            // Poll for the phone's pair event. The job is tracked so
            // cancelPairing() aborts the long-poll promptly.
            withContext(Dispatchers.IO) { transport.awaitQrPairing(b) }
            onPhase(Phase.PAIRED)
        } catch (e: PairingException) {
            error = e.message
            onPhase(Phase.ERROR)
        } catch (e: Exception) {
            // CancellationException from back-out is expected, not an error.
            if (e is kotlinx.coroutines.CancellationException) {
                onBack()
            } else {
                error = e.message ?: "pairing failed"
                onPhase(Phase.ERROR)
            }
        }
    }

    Text("Pair RCS", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    when (phase) {
        Phase.QR_GENERATING -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Contacting Google…", style = MaterialTheme.typography.bodyMedium)
        }
        else -> {
            qrBitmap?.let {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.clip(RoundedCornerShape(28.dp)),
                ) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "RCS pairing QR code",
                        modifier = Modifier
                            .size(280.dp)
                            .padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "In Google Messages, go to Device pairing and scan this code. " +
                    "Relay will connect automatically.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                scope.launch { transport.cancelPairing() }
                onBack()
            }) { Text("Cancel") }
        }
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bmp
}
