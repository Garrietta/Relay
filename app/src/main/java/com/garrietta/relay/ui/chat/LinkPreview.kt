package com.garrietta.relay.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Link unfurl previews (Garrett 2026-10-19): messages containing a URL get
 * a preview card — page title + preview image + domain, like Google
 * Messages. His hypothesis was that some "corrupt images" are actually
 * broken link previews, so the failure contract is strict: a preview that
 * can't be fetched or whose image won't decode degrades to a plain tappable
 * link row — it NEVER renders as a gray/corrupt block.
 */

/** A fetched unfurl: title + optional preview image. Null = fetch failed. */
data class LinkPreview(
    val url: String,
    val title: String?,
    val imageUrl: String?,
)

object LinkPreviewFetcher {
    private val urlPattern =
        Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

    /** First URL in [text], or null. */
    fun extractFirstUrl(text: String): String? =
        urlPattern.find(text)?.value?.trimEnd('.', ',', ';', '!', '?')

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Small LRU so scrolling back over link messages doesn't re-fetch.
    private val cache = object : LinkedHashMap<String, LinkPreview?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreview?>) =
            size > 64
    }

    /** Fetches + parses the unfurl. Null on ANY failure — callers degrade. */
    suspend fun preview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(url)) return@withContext cache[url] }
        val result = try {
            fetchPreview(url)
        } catch (_: Exception) {
            null
        }
        synchronized(cache) { cache[url] = result }
        result
    }

    private fun fetchPreview(url: String): LinkPreview? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Relay/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true)) return null
            val body = resp.body ?: return null
            // Cap the download: we only need the <head>.
            val html = body.source().let { src ->
                val buf = StringBuilder()
                val tmp = ByteArray(8192)
                var total = 0
                while (total < 262_144) {
                    val n = src.read(tmp)
                    if (n == -1) break
                    buf.append(String(tmp, 0, n, Charsets.UTF_8))
                    total += n
                    // Stop early once </head> is seen.
                    if (buf.contains("</head>", ignoreCase = true)) break
                }
                buf.toString()
            }
            val finalUrl = resp.request.url.toString()
            val title = ogContent(html, "og:title")
                ?: htmlTitle(html)
                ?: ogContent(html, "twitter:title")
            var image = ogContent(html, "og:image")
                ?: ogContent(html, "twitter:image")
            image = image?.let { resolve(finalUrl, it) }
            // A preview with neither title nor image is useless — but the
            // URL itself is still real, so return a title-less preview and
            // let the card degrade to the plain link row.
            return LinkPreview(url = finalUrl, title = title, imageUrl = image)
        }
    }

    private fun ogContent(html: String, property: String): String? {
        // Handles both attribute orders: property-then-content and
        // content-then-property.
        val p1 = Regex(
            """<meta[^>]+property=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        if (p1 != null) return p1
        return Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$property["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
    }

    private fun htmlTitle(html: String): String? =
        Regex("""<title[^>]*>([^<]{1,200})</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URL(java.net.URL(base), ref).toString()
        } catch (_: Exception) {
            ref
        }
    }
}

/**
 * The unfurl card under a message bubble. States:
 * - loading: compact shimmer-less placeholder (fixed small height, no gray
 *   block — just the domain row skeleton);
 * - loaded: image (Coil — it never renders corrupt bytes) + title + domain;
 * - failed / no title+image: plain tappable link row with the domain.
 * Tapping anywhere opens the URL in the default browser.
 */
@Composable
fun LinkPreviewCard(
    url: String,
    isOutgoing: Boolean,
    maxBubbleWidth: Dp = Dp.Unspecified,
) {
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var imageBroken by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val p = LinkPreviewFetcher.preview(url)
        if (p == null || (p.title == null && p.imageUrl == null)) failed = true
        else preview = p
    }

    fun open() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Exception) {
        }
    }

    val domain = remember(url) {
        try {
            java.net.URL(url).host.removePrefix("www.")
        } catch (_: Exception) {
            url
        }
    }
    val cardColor = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    when {
        failed || (preview != null && preview!!.title == null && (preview!!.imageUrl == null || imageBroken)) -> {
            // Plain link row — the graceful degradation. Never a gray box.
            Row(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::open,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    domain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = "Open link",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        preview != null -> {
            val p = preview!!
            Column(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::open,
                    ),
            ) {
                if (p.imageUrl != null && !imageBroken) {
                    // Coil decodes defensively: undecodable bytes show the
                    // error fallback (nothing), never a corrupt render.
                    var imgFailed by remember(p.imageUrl) { mutableStateOf(false) }
                    LaunchedEffect(imgFailed) { if (imgFailed) imageBroken = true }
                    if (!imgFailed) {
                        AsyncImage(
                            model = p.imageUrl,
                            contentDescription = "Link preview image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                            onError = { imgFailed = true },
                        )
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        p.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        domain,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        else -> {
            // Loading: slim placeholder row — fixed height, theme-colored,
            // never a gray image block.
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardColor.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
