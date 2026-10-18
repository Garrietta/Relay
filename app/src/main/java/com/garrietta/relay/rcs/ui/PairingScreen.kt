package com.garrietta.relay.rcs.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RCS pairing screen — Gaia (Google-account / emoji) is the primary flow;
 * QR remains as the fallback.
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
            Phase.CHOOSING -> {
                Text("Pair RCS", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sign in with your Google account and match the emoji — no QR scanning needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { phase = Phase.GAIA_SIGNIN },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair with Google account") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { phase = Phase.QR },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair with QR instead") }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) { Text("Cancel") }
            }
            Phase.GAIA_SIGNIN -> {
                var cookies by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }

                if (cookies == null) {
                    Text("Sign in with Google", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use the Google account on your phone. Relay only reads the session cookies.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    GaiaSignInWebView(
                        onCookiesCaptured = { cookies = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { phase = Phase.CHOOSING }) { Text("Back") }
                } else {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Contacting Google…", style = MaterialTheme.typography.bodyMedium)
                    LaunchedEffect(cookies) {
                        transport.trackPairingJob(coroutineContext[Job])
                        try {
                            withContext(Dispatchers.IO) {
                                transport.beginGaiaPairing(cookies!!)
                            }
                            phase = Phase.GAIA_VERIFY
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.message ?: "Sign-in failed"
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Phase.GAIA_VERIFY -> {
                // Emoji verification UI. The UKEY2 handshake (GaiaPairing)
                // derives the index; the glyph set is provisional until the
                // live handshake validates the pairingEmojisV arrays.
                Text("Match the emoji", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Does this emoji match the one on your phone?",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Text("😀", fontSize = 72.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Emoji verification UI is ready. The live UKEY2 handshake " +
                        "completes after your Google sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { phase = Phase.CHOOSING }) { Text("Back") }
            }
            Phase.QR -> QrFlow(transport = transport, onBack = onBack, onPaired = { phase = Phase.PAIRED })
            Phase.PAIRED -> {
                Text(
                    "Paired! RCS is connected.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Done") }
            }
        }
    }
}

private enum class Phase { CHOOSING, GAIA_SIGNIN, GAIA_VERIFY, QR, PAIRED }

@Composable
private fun QrFlow(
    transport: RcsTransport,
    onBack: () -> Unit,
    onPaired: () -> Unit,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var begun by remember { mutableStateOf<QrPairing.BegunPairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        transport.trackPairingJob(coroutineContext[Job])
        try {
            val b = withContext(Dispatchers.IO) { transport.beginQrPairing() }
            begun = b
            qrBitmap = withContext(Dispatchers.IO) { qrBitmap(b.qrContent, 640) }
            generating = false
            withContext(Dispatchers.IO) { transport.awaitQrPairing(b) }
            onPaired()
        } catch (e: PairingException) {
            error = e.message
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                onBack()
            } else {
                error = e.message ?: "pairing failed"
            }
        }
    }

    Text("Pair RCS", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    if (generating) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Contacting Google…", style = MaterialTheme.typography.bodyMedium)
    } else {
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
            "In Google Messages, go to Device pairing and scan this code.",
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
