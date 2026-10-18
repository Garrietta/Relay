package com.garrietta.relay.rcs.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Google sign-in WebView for Gaia pairing.
 *
 * Tactical notes from the protocol spec (§1b):
 *  - Spoof a desktop Chrome UA — Google's OAuth endpoint returns
 *    "disallowed_useragent" for the stock WebView UA.
 *  - Intercept cookies via CookieManager once the account chooser /
 *    consent flow lands on a Google domain; the SAPISID-family cookies
 *    are what SignInGaia needs.
 *  - Destroy the WebView after capture — it must not linger with a live
 *    Google session.
 *
 * The sign-in itself is Garrett's manual step; this composable only hosts
 * it and reports the captured cookie header.
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
            // Desktop Chrome UA: avoids disallowed_useragent on Google OAuth.
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    // SAPISID* cookies signal a signed-in Google session.
                    if (!cookies.isNullOrBlank() && cookies.contains("SAPISID")) {
                        onCookiesCaptured(cookies)
                    }
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

/** Reads all Google cookies currently in the CookieManager. */
fun readGoogleCookies(): String =
    CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty()
