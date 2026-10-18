package com.garrietta.relay.rcs.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal functional RCS pairing screen (QR flow).
 *
 * Design polish comes later; this screen's job is to get Garrett paired in
 * the morning: it shows the QR, polls for the phone's scan, and reports
 * success/failure plainly. A Gaia (emoji-match) method slot is reserved below
 * the QR section for the later phase.
 */
@Composable
fun RcsPairingScreen(
    transport: RcsTransport,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.GENERATING) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var begun by remember { mutableStateOf<QrPairing.BegunPairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val b = withContext(Dispatchers.IO) { transport.beginQrPairing() }
            begun = b
            qrBitmap = withContext(Dispatchers.IO) { qrBitmap(b.qrContent, 640) }
            phase = Phase.AWAITING_SCAN
            // Poll for the phone's pair event.
            withContext(Dispatchers.IO) { transport.awaitQrPairing(b) }
            phase = Phase.PAIRED
        } catch (e: PairingException) {
            error = e.message
            phase = Phase.ERROR
        } catch (e: Exception) {
            error = e.message ?: "pairing failed"
            phase = Phase.ERROR
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Pair RCS",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        when (phase) {
            Phase.GENERATING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Contacting Google…", style = MaterialTheme.typography.bodyMedium)
            }
            Phase.AWAITING_SCAN -> {
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
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back") }
            }
        }
    }
}

private enum class Phase { GENERATING, AWAITING_SCAN, PAIRED, ERROR }

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
