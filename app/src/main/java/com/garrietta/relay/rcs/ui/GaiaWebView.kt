package com.garrietta.relay.rcs.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google sign-in WebView for Gaia pairing.
 *
 * Google's "This browser or app may not be secure" page is Google's
 * embedded-browser policy, not a Relay bug: Google sniffs WebViews via the
 * `; wv` UA token and the `X-Requested-With` request header (which WebView
 * sets to the app's package name). This file is ONE hardening pass against
 * that detection:
 *  - a current mobile-Chrome UA with no `; wv` token;
 *  - [shouldInterceptRequest] re-issues Google GETs itself, forwarding every
 *    header EXCEPT `X-Requested-With`, so Google never sees the WebView
 *    fingerprint;
 *  - SAPISID-family cookies are captured from the CookieManager once the
 *    account flow lands on a Google domain; the WebView is destroyed after.
 *
 * The sign-in itself is Garrett's manual step; this composable only hosts it
 * and reports the captured cookie header. If Google still refuses, the QR
 * flow (Google's own device-pairing path) becomes the primary — do not burn
 * more effort trying to bypass Google's policy.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GaiaSignInWebView(
    onCookiesCaptured: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Current mobile-Chrome UA, no `; wv` WebView token.
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                /**
                 * Strip the WebView fingerprint Google uses to block embedded
                 * browsers. WebView injects `X-Requested-With: <package>` on
                 * every request; re-issuing the request ourselves drops it.
                 * Only Google GETs are re-issued — everything else (POSTs,
                 * non-Google hosts) falls through untouched.
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val host = request.url.host.orEmpty()
                    val isGoogle = host.endsWith("google.com") ||
                        host.endsWith("gstatic.com") ||
                        host.endsWith("googleapis.com")
                    if (!isGoogle || request.method != "GET") return null
                    return try {
                        val conn = URL(request.url.toString()).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", view.settings.userAgentString)
                        for ((name, value) in request.requestHeaders) {
                            if (name.equals("X-Requested-With", ignoreCase = true)) continue
                            conn.setRequestProperty(name, value)
                        }
                        conn.connect()
                        val contentType = conn.contentType ?: "text/html"
                        val mime = contentType.substringBefore(";").trim()
                        val charset = contentType.substringAfter("charset=", "").trim()
                            .takeIf { it.isNotEmpty() }
                        WebResourceResponse(mime, charset, conn.inputStream)
                    } catch (_: Exception) {
                        // Fall through to the default loader on any failure.
                        null
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    captureCookies(url, onCookiesCaptured)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    captureCookies(url, onCookiesCaptured)
                }
            }
            // Google's account sign-in entry point.
            loadUrl("https://accounts.google.com/ServiceLogin?service=mail&passive=true")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            // Kill the WebView after capture — no lingering Google session.
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
    )
}

/** SAPISID* cookies signal a signed-in Google session — report them once seen. */
private fun captureCookies(url: String, onCookiesCaptured: (String) -> Unit) {
    val cookies = CookieManager.getInstance().getCookie(url)
    if (!cookies.isNullOrBlank() && cookies.contains("SAPISID")) {
        onCookiesCaptured(cookies)
    }
}

/** Reads all Google cookies currently in the CookieManager. */
fun readGoogleCookies(): String =
    CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty()
