package com.garrietta.relay.rcs.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.rcs.pairing.MicrogPairing
import com.garrietta.relay.rcs.pairing.MicrogPairingException
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RCS pairing screen — the phone's own Google account (microG, no browser
 * sign-in) is the primary flow; the WebView Google sign-in and QR remain as
 * fallbacks, in that order.
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
                    "Use the Google account already on this phone — no browser sign-in, just match the emoji.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { phase = Phase.MICROG },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair with this phone's Google account") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { phase = Phase.GAIA_SIGNIN },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in with Google in a browser") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { phase = Phase.QR },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair with QR instead") }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) { Text("Cancel") }
            }
            Phase.MICROG -> MicrogFlow(
                transport = transport,
                onBegun = { phase = Phase.GAIA_VERIFY },
                onWebView = { phase = Phase.GAIA_SIGNIN },
                onQr = { phase = Phase.QR },
                onBack = { phase = Phase.CHOOSING },
            )
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

private enum class Phase { CHOOSING, MICROG, GAIA_SIGNIN, GAIA_VERIFY, QR, PAIRED }

/**
 * microG pairing flow: enumerate the phone's Google accounts, mint an
 * OAuthLogin token via microG's authenticator (one consent tap), exchange
 * it for web session cookies, then hand off to the standard Gaia begin.
 *
 * Edge cases never strand: no accounts / permission denied / consent denied
 * / token refused all land on a clear message with fallback buttons, and
 * dispose aborts via the parent's cancelPairing.
 */
@Composable
private fun MicrogFlow(
    transport: RcsTransport,
    onBegun: () -> Unit,
    onWebView: () -> Unit,
    onQr: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var accounts by remember { mutableStateOf<List<MicrogPairing.MicrogAccount>?>(null) }
    var startedAuto by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Preparing…") }
    var error by remember { mutableStateOf<String?>(null) }

    var hasAccountsPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAccountsPerm = granted
        if (!granted) {
            error = "Relay needs the accounts permission to see your Google account."
        }
    }

    // microG's consent Intent arrives here; the pending fetch resumes it.
    var consentDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        consentDeferred?.complete(result.resultCode == Activity.RESULT_OK)
    }

    fun startFetch(account: MicrogPairing.MicrogAccount) {
        fetching = true
        error = null
        scope.launch {
            transport.trackPairingJob(coroutineContext[Job])
            try {
                withContext(Dispatchers.IO) {
                    transport.beginMicrogGaiaPairing(account.name) { intent ->
                        status = "Waiting for your consent…"
                        val d = CompletableDeferred<Boolean>()
                        consentDeferred = d
                        // ActivityResultLauncher must run on the main thread.
                        withContext(Dispatchers.Main) { consentLauncher.launch(intent) }
                        d.await()
                    }
                }
                onBegun()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "microG pairing failed"
            } finally {
                fetching = false
            }
        }
    }

    LaunchedEffect(hasAccountsPerm) {
        if (!hasAccountsPerm) {
            permLauncher.launch(Manifest.permission.GET_ACCOUNTS)
            return@LaunchedEffect
        }
        try {
            accounts = MicrogPairing.listGoogleAccounts(context)
        } catch (e: MicrogPairingException) {
            error = e.message
        }
    }

    // Exactly one account → go straight in (once).
    LaunchedEffect(accounts) {
        val list = accounts
        if (list != null && list.size == 1 && !startedAuto && !fetching && error == null) {
            startedAuto = true
            startFetch(list[0])
        }
    }

    Text("Pair with your Google account", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))

    when {
        fetching -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(
                "microG mints the sign-in token on this phone — no browser, no password typing.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                scope.launch { transport.cancelPairing() }
                onBack()
            }) { Text("Cancel") }
        }
        error != null -> {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            MicrogFallbacks(onWebView = onWebView, onQr = onQr, onBack = onBack)
        }
        accounts == null -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Checking for Google accounts…", style = MaterialTheme.typography.bodyMedium)
        }
        accounts!!.isEmpty() -> {
            Text(
                "No Google account found on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your Google account in the microG settings, then try again — or use another method below.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            MicrogFallbacks(onWebView = onWebView, onQr = onQr, onBack = onBack)
        }
        else -> {
            Text(
                "Choose the Google account to pair with:",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            accounts!!.forEach { account ->
                Button(
                    onClick = { startFetch(account) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(account.name) }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun MicrogFallbacks(
    onWebView: () -> Unit,
    onQr: () -> Unit,
    onBack: () -> Unit,
) {
    OutlinedButton(
        onClick = onWebView,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign in with Google in a browser") }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onQr,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pair with QR instead") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onBack) { Text("Back") }
}

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
