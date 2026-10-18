package com.garrietta.relay.rcs.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.garrietta.relay.rcs.proto.DeviceInfo
import java.util.Base64

/**
 * Persisted pairing/session state for the RCS transport.
 *
 * Mirrors the reference implementation's AuthData (request crypto keys, ECDSA
 * refresh key, Tachyon token + expiry, device protos, session/pairing UUIDs).
 * Stored in EncryptedSharedPreferences — these keys authenticate the session.
 */
data class AuthData(
    val aesKey: ByteArray,
    val hmacKey: ByteArray,
    /** ECDSA P-256 refresh private key, PKCS#8. */
    val refreshPrivateKey: ByteArray,
    /** ECDSA P-256 refresh public key, X.509. */
    val refreshPublicKey: ByteArray,
    val tachyonToken: ByteArray,
    /** Epoch millis when the Tachyon token should be refreshed. */
    val tachyonExpiryMs: Long,
    val sessionId: String,
    val pairingId: String,
    /** Binary Device protos, base64. */
    val mobileB64: String,
    val browserB64: String,
) {
    val hasToken: Boolean get() = tachyonToken.isNotEmpty()
}

class AuthStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "relay_rcs_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()
    private fun put(k: String, v: ByteArray) = prefs.edit().putString(k, b64.encodeToString(v)).apply()
    private fun get(k: String): ByteArray? = prefs.getString(k, null)?.let(unb64::decode)

    fun save(a: AuthData) {
        put("aes", a.aesKey); put("hmac", a.hmacKey)
        put("refresh_priv", a.refreshPrivateKey); put("refresh_pub", a.refreshPublicKey)
        put("tachyon", a.tachyonToken)
        prefs.edit()
            .putLong("tachyon_expiry", a.tachyonExpiryMs)
            .putString("session_id", a.sessionId)
            .putString("pairing_id", a.pairingId)
            .putString("mobile", a.mobileB64)
            .putString("browser", a.browserB64)
            .apply()
    }

    fun load(): AuthData? {
        val aes = get("aes") ?: return null
        val hmac = get("hmac") ?: return null
        val priv = get("refresh_priv") ?: return null
        val pub = get("refresh_pub") ?: return null
        val token = get("tachyon") ?: return null
        return AuthData(
            aesKey = aes, hmacKey = hmac,
            refreshPrivateKey = priv, refreshPublicKey = pub,
            tachyonToken = token,
            tachyonExpiryMs = prefs.getLong("tachyon_expiry", 0),
            sessionId = prefs.getString("session_id", "") ?: "",
            pairingId = prefs.getString("pairing_id", "") ?: "",
            mobileB64 = prefs.getString("mobile", "") ?: "",
            browserB64 = prefs.getString("browser", "") ?: "",
        )
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        fun decodeDevice(b64: String): DeviceInfo {
            if (b64.isEmpty()) return DeviceInfo()
            return try {
                val bytes = Base64.getDecoder().decode(b64)
                com.garrietta.relay.rcs.proto.decodeDevice(bytes)
            } catch (e: Exception) {
                DeviceInfo()
            }
        }
    }
}
