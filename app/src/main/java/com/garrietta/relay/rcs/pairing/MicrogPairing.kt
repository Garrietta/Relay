package com.garrietta.relay.rcs.pairing

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.os.BundleCompat
import com.garrietta.relay.rcs.net.RelayApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google-account pairing WITHOUT any browser or WebView sign-in.
 *
 * Uses the Google account already on the phone (microG's native
 * authenticator) to mint Chromium's own "OAuthLogin"-scoped token, then
 * runs the exact token→cookie exchange Chromium uses
 * (`accounts.google.com/o/oauth2/multilogin`). The resulting web session
 * cookies feed the UNCHANGED Gaia pairing path (GaiaPairing.begin).
 *
 * Chain:
 *  1. AccountManager.getAuthToken(account, "oauth2:https://www.google.com/accounts/OAuthLogin")
 *     → microG's AccountAuthenticator → android.clients.google.com/auth.
 *     First use for a new scope surfaces microG's AskPermissionActivity
 *     (one consent tap, no browser); handled via [launchConsent].
 *  2. GET https://www.googleapis.com/oauth2/v3/userinfo → "sub" claim =
 *     the obfuscated GAIA ID for the MultiBearer pair.
 *  3. POST https://accounts.google.com/o/oauth2/multilogin?source=RelaySignin&reuseCookies=0
 *     with `Authorization: MultiBearer <token>:<gaia_id>`
 *     → XSSI-protected JSON {"status":"OK","cookies":[{name,value},…]}.
 *  4. Cookies formatted as a Cookie header string → GaiaPairing.begin.
 *
 * THE EXPERIMENT: step 1's server-side decision is Google's — whether
 * `android.clients.google.com/auth` issues the OAuthLogin scope to Relay's
 * third-party signature. If Google refuses, the error surfaces verbatim
 * (INVALID_TOKENS / userinfo HTTP 4xx) and the verdict flips to NOT VIABLE.
 *
 * Nothing here touches credentials beyond Google's own endpoints: the token
 * never leaves the device except to accounts.google.com / googleapis.com.
 */
object MicrogPairing {
    private const val TAG = "MicrogPairing"

    /** Chromium's multilogin scope (kOAuth1LoginScope) — the token type whose
     * sole purpose is bootstrapping web sessions. */
    const val OAUTH_LOGIN_SCOPE = "oauth2:https://www.google.com/accounts/OAuthLogin"
    const val ACCOUNT_TYPE_GOOGLE = "com.google"

    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
    private const val MULTILOGIN_URL = "https://accounts.google.com/o/oauth2/multilogin"

    data class MicrogAccount(val name: String)

    /**
     * Google accounts visible to Relay (microG registers type "com.google").
     * Throws [MicrogPairingException] when the accounts permission is missing.
     */
    fun listGoogleAccounts(context: Context): List<MicrogAccount> {
        return try {
            AccountManager.get(context)
                .getAccountsByType(ACCOUNT_TYPE_GOOGLE)
                .map { MicrogAccount(it.name) }
        } catch (e: SecurityException) {
            throw MicrogPairingException(
                "Relay can't see your Google accounts — the accounts permission wasn't granted.", e
            )
        }
    }

    /** Single account → it; zero or many → null (caller shows empty-state / picker). */
    fun chooseSingleAccount(accounts: List<MicrogAccount>): MicrogAccount? =
        if (accounts.size == 1) accounts[0] else null

    /**
     * Full chain: token → GAIA ID → multilogin cookies.
     *
     * @param launchConsent invoked with microG's consent Intent when the
     * authenticator demands it; suspends until the user answers; returns true
     * if consent was granted. MUST be called on the main thread by the caller
     * (ActivityResultLauncher requirement) — this function switches context.
     * @param onProgress human-readable step updates for the UI.
     * @return Cookie header value ("SID=…; HSID=…; …") for GaiaPairing.begin.
     */
    suspend fun fetchCookies(
        context: Context,
        accountName: String,
        launchConsent: suspend (Intent) -> Boolean,
        onProgress: (String) -> Unit = {},
        http: OkHttpClient = RelayApi.client(),
    ): String = withContext(Dispatchers.IO) {
        val am = AccountManager.get(context)
        val account = Account(accountName, ACCOUNT_TYPE_GOOGLE)

        onProgress("Asking microG for a sign-in token…")
        val token = mintOAuthLoginToken(am, account, launchConsent)
        Log.i(TAG, "OAuthLogin token minted (${token.length} chars)")
        try {
            onProgress("Resolving your Google account…")
            val gaiaId = fetchGaiaId(http, token)
            onProgress("Exchanging the token for a web session…")
            val cookies = multilogin(http, token, gaiaId, am)
            if (cookies.isEmpty()) {
                throw MicrogPairingException("Google returned no cookies — pairing can't continue.")
            }
            Log.i(TAG, "multilogin OK: ${cookies.size} cookies (${cookies.keys.joinToString()})")
            formatCookieHeader(cookies)
        } catch (e: MicrogPairingException) {
            throw e
        } catch (e: Exception) {
            throw MicrogPairingException("Google session exchange failed: ${e.message}", e)
        }
    }

    /**
     * Mints the OAuthLogin-scoped token via microG's authenticator. Handles
     * the consent flow: if the result carries KEY_INTENT, [launchConsent]
     * shows microG's AskPermissionActivity and we retry once.
     */
    private suspend fun mintOAuthLoginToken(
        am: AccountManager,
        account: Account,
        launchConsent: suspend (Intent) -> Boolean,
    ): String {
        var consentRetries = 0
        while (true) {
            val bundle = suspendCancellableCoroutine<Bundle> { cont ->
                val future = am.getAuthToken(
                    account, OAUTH_LOGIN_SCOPE, null, null,
                    AccountManagerCallback { f ->
                        try {
                            // NOTE: this project's coroutines version has no
                            // default for onCancellation, and resumeWithException
                            // doesn't resolve here — pass null explicitly and
                            // use the Continuation.resumeWith member instead.
                            cont.resume(f.result, null)
                        } catch (e: Exception) {
                            cont.resumeWith(Result.failure(e))
                        }
                    },
                    null,
                )
                cont.invokeOnCancellation { future.cancel(true) }
            }

            bundle.getString(AccountManager.KEY_AUTHTOKEN)?.let { return it }

            val intent = BundleCompat.getParcelable(bundle, AccountManager.KEY_INTENT, Intent::class.java)
            if (intent != null) {
                if (consentRetries >= 1) {
                    throw MicrogPairingException("Consent didn't produce a token — please try again.")
                }
                consentRetries++
                val granted = withContext(Dispatchers.Main) { launchConsent(intent) }
                if (!granted) {
                    throw MicrogPairingException("Cancelled — Relay needs your one-tap consent to mint the sign-in token.")
                }
                continue // retry now that consent was granted
            }

            val code = bundle.getInt(AccountManager.KEY_ERROR_CODE, -1)
            val msg = bundle.getString(AccountManager.KEY_ERROR_MESSAGE)
            // This is the experiment's failure mode: Google refusing the scope
            // to a third-party signature surfaces here.
            throw MicrogPairingException(
                "microG couldn't mint the sign-in token (code=$code): ${msg ?: "no details"}. " +
                    "If Google refused the OAuthLogin scope for Relay, this path isn't viable — use QR instead."
            )
        }
    }

    private fun fetchGaiaId(http: OkHttpClient, token: String): String {
        val req = Request.Builder()
            .url(USERINFO_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body!!.string()
            if (!resp.isSuccessful) {
                throw MicrogPairingException(
                    "Google rejected the token at userinfo (HTTP ${resp.code}) — " +
                        "the OAuthLogin scope was probably not issued to Relay."
                )
            }
            return parseUserinfoSub(body)
        }
    }

    private fun multilogin(
        http: OkHttpClient,
        token: String,
        gaiaId: String,
        am: AccountManager,
    ): Map<String, String> {
        // Non-empty body forces a real POST (mirrors Chromium's fetcher).
        val form = FormBody.Builder().add("source", "RelaySignin").build()
        val req = Request.Builder()
            .url("$MULTILOGIN_URL?source=RelaySignin&reuseCookies=0")
            .header("Authorization", "MultiBearer $token:$gaiaId")
            .post(form)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body!!.string()
            if (!resp.isSuccessful) {
                throw MicrogPairingException(
                    "Google refused the token→cookie exchange (HTTP ${resp.code})."
                )
            }
            try {
                return parseMultiloginCookies(body)
            } catch (e: MicrogPairingException) {
                // A bad token poisons microG's cache — drop it so a retry mints fresh.
                if (e.message?.contains("INVALID_TOKENS") == true) {
                    runCatching { am.invalidateAuthToken(ACCOUNT_TYPE_GOOGLE, token) }
                }
                throw e
            }
        }
    }

    // ---------- pure helpers (unit-tested) ----------
    // NOTE: parsed with Json.parseToJsonElement (no @Serializable codegen —
    // this module doesn't apply the serialization compiler plugin).

    /** Strips the XSSI guard ")]}',\n" prefix Google prepends to JSON responses. */
    fun stripXssi(body: String): String {
        val t = body.trimStart()
        return if (t.startsWith(")]}'")) t.substringAfter('\n') else t
    }

    fun parseUserinfoSub(body: String): String {
        val sub = Json.parseToJsonElement(stripXssi(body))
            .jsonObject["sub"]?.jsonPrimitive?.contentOrNull
        if (sub.isNullOrBlank()) throw MicrogPairingException("Google userinfo had no account id.")
        return sub
    }

    /**
     * Parses the multilogin response into name→value cookies.
     * Throws [MicrogPairingException] with the server's status otherwise —
     * INVALID_TOKENS is the experiment's negative signal, surfaced verbatim.
     */
    fun parseMultiloginCookies(body: String): Map<String, String> {
        val obj = Json.parseToJsonElement(stripXssi(body)).jsonObject
        return when (val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "") {
            "OK" -> obj["cookies"]?.jsonArray?.associate { c ->
                val co = c.jsonObject
                (co["name"]?.jsonPrimitive?.contentOrNull ?: "") to
                    (co["value"]?.jsonPrimitive?.contentOrNull ?: "")
            }?.filterKeys { it.isNotEmpty() } ?: emptyMap()
            "INVALID_TOKENS" -> throw MicrogPairingException(
                "Google said INVALID_TOKENS — the minted token wasn't accepted for a web session. " +
                    "This means Google won't issue the OAuthLogin scope to Relay: the microG path is NOT viable, use QR."
            )
            else -> throw MicrogPairingException("Google multilogin failed: status=$status")
        }
    }

    /** "SID=…; HSID=…; …" — the exact format GaiaPairing.begin expects. */
    fun formatCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** User-readable failure from the microG pairing chain. Message is UI-safe. */
class MicrogPairingException(message: String, cause: Throwable? = null) : Exception(message, cause)
