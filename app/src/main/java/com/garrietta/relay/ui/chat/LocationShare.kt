package com.garrietta.relay.ui.chat

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.model.locationMapsLink
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Location sharing (Garrett 2026-10-20, feature queue 6/7): one-shot
 * current-location pins from the composer [+] sheet. No Play-services
 * dependency (Relay pairs with a microG device) and no static-map API
 * key — the fix comes from the platform [LocationManager], the label
 * from the platform [Geocoder] when it has a backend, and the "map" is
 * a stylized in-app card. One-shot only: nothing here ever tracks or
 * updates in the background.
 */

/** A location failure whose message is shown to Garrett verbatim. */
class LocationException(message: String) : Exception(message)

private val FixProviders = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)

/** The freshest last-known fix across providers, or null. */
fun lastKnownLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    var best: Location? = null
    for (provider in FixProviders + LocationManager.PASSIVE_PROVIDER) {
        val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            ?: continue
        if (best == null || loc.time > best.time) best = loc
    }
    return best
}

/**
 * One-shot current fix: a last-known fix under 30s old wins immediately;
 * otherwise a single update is awaited from every enabled provider
 * (first fix wins, 20s budget), falling back to any last-known fix when
 * the wait times out. Throws [LocationException] with a verbatim,
 * user-actionable reason when no fix is obtainable.
 */
suspend fun awaitCurrentLocation(context: Context): Location {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: throw LocationException("This device has no location service.")
    val last = lastKnownLocation(context)
    if (last != null &&
        System.currentTimeMillis() - last.time < 30_000
    ) {
        return last
    }
    val enabled = FixProviders.filter {
        runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
    }
    if (enabled.isEmpty()) {
        if (last != null) return last
        throw LocationException(
            "Location is turned off on this device — turn on Location in " +
                "system Settings to share your location.",
        )
    }
    var removeListener: () -> Unit = {}
    val fix = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (cont.isActive) cont.resume(loc)
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit

                @Deprecated("Deprecated in the framework; required by the interface")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) = Unit
            }
            removeListener = { runCatching { lm.removeUpdates(listener) } }
            try {
                for (provider in enabled) {
                    lm.requestLocationUpdates(
                        provider, 0L, 0f, listener, Looper.getMainLooper(),
                    )
                }
            } catch (e: SecurityException) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        LocationException(
                            "Location permission isn't granted — allow " +
                                "location access for Relay to share your location.",
                        ),
                    )
                }
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { removeListener() }
        }
    }
    removeListener()
    return fix ?: last ?: throw LocationException(
        "Couldn't get a location fix — check that Location is on and try again.",
    )
}

/**
 * Best-effort human label for coordinates via the platform geocoder
 * (no API key). Null when the device has no geocoder backend or the
 * lookup fails — the pin then travels as coordinates only.
 */
suspend fun reverseGeocodeLabel(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        @Suppress("DEPRECATION")
        val results = Geocoder(context, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)
        val a = results?.firstOrNull() ?: return@runCatching null
        a.getAddressLine(0)?.ifBlank { null }
            ?: listOfNotNull(
                a.locality ?: a.subAdminArea,
                a.adminArea,
            ).filter { it.isNotBlank() }
                .joinToString(", ")
                .ifBlank { null }
    }.getOrNull()
}

/** Opens the pin in the default maps app (geo: intent), falling back to the Maps web link. */
fun openLocationInMaps(context: Context, latitude: Double, longitude: Double, name: String) {
    val q = if (name.isNotBlank()) "$latitude,$longitude($name)" else "$latitude,$longitude"
    val geoIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:$latitude,$longitude?q=" + Uri.encode(q)),
    )
    runCatching { context.startActivity(geoIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(locationMapsLink(latitude, longitude))),
            )
        }
    }
}

/**
 * The location picker sheet: shows the current fix on a stylized map
 * card (drawn in-app — no map tiles, no API key), the geocoded label
 * when available (coordinates otherwise), and the send action. SMS
 * chats are told up front that the pin travels as a Maps link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationShareSheet(
    isSmsChat: Boolean,
    onDismiss: () -> Unit,
    onSend: (latitude: Double, longitude: Double, name: String?) -> Unit,
) {
    val context = LocalContext.current
    var fix by remember { mutableStateOf<Location?>(null) }
    var label by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        // Instant paint from a last-known fix while the fresh one resolves.
        if (fix == null) fix = lastKnownLocation(context)
        try {
            val fresh = awaitCurrentLocation(context)
            fix = fresh
            label = reverseGeocodeLabel(context, fresh.latitude, fresh.longitude)
        } catch (e: Exception) {
            // A seeded last-known fix stays sendable; only a total
            // failure surfaces the verbatim reason.
            if (fix == null) {
                error = e.message ?: "Couldn't get a location fix — try again."
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = "Location",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Share location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(16.dp))
            // Stylized map placeholder: container-tone card, a few drawn
            // "roads", and the pin — no tiles, no key, no network.
            val roadColor = MaterialTheme.colorScheme.onTertiaryContainer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(148.dp)) {
                    val w = size.width
                    val h = size.height
                    val c = roadColor.copy(alpha = 0.28f)
                    drawLine(c, Offset(0f, h * 0.30f), Offset(w, h * 0.42f), 10f)
                    drawLine(c, Offset(0f, h * 0.78f), Offset(w, h * 0.62f), 14f)
                    drawLine(c, Offset(w * 0.24f, 0f), Offset(w * 0.38f, h), 8f)
                    drawLine(c, Offset(w * 0.72f, 0f), Offset(w * 0.64f, h), 12f)
                    drawCircle(
                        roadColor.copy(alpha = 0.16f),
                        radius = 54f,
                        center = Offset(w / 2, h / 2),
                    )
                    drawCircle(
                        roadColor.copy(alpha = 0.10f),
                        radius = 96f,
                        center = Offset(w / 2, h / 2),
                    )
                }
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = "Your location",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(46.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            val currentFix = fix
            when {
                error != null -> {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { attempt++ }) {
                        Text("Try again")
                    }
                }
                currentFix == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SevenShapeLoadingIndicator(diameter = 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Finding your location…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    label?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = String.format(
                            Locale.US, "%.5f, %.5f",
                            currentFix.latitude, currentFix.longitude,
                        ) + if (currentFix.hasAccuracy()) {
                            String.format(Locale.US, " · ±%.0f m", currentFix.accuracy)
                        } else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isSmsChat) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "This chat is SMS — your location will be sent as a Maps link.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onSend(currentFix.latitude, currentFix.longitude, label)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send current location")
                    }
                }
            }
        }
    }
}
