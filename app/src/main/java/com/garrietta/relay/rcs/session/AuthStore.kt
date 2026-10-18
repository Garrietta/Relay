package com.garrietta.relay.rcs.session

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.garrietta.relay.rcs.proto.DeviceInfo
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persisted pairing/session state for the RCS transport.
 *
 * Mirrors the reference implementation's AuthData (request crypto keys, ECDSA
 * refresh key, Tachyon token + expiry, device protos, session/pairing UUIDs).
 *
 * Values are AES-256-GCM encrypted with a key held in the AndroidKeyStore and
 * stored as base64 in private SharedPreferences — no extra dependency, and the
 * key material never sits in the clear on disk.
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
    private val prefs: SharedPreferences =
        context.getSharedPreferences("relay_rcs_auth", Context.MODE_PRIVATE)
    private val masterKey: SecretKey by lazy { getOrCreateKeyStoreKey() }

    private fun putEncrypted(name: String, plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val blob = cipher.iv + cipher.doFinal(plain)
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(blob)).apply()
    }

    private fun getEncrypted(name: String): ByteArray? {
        val blob = prefs.getString(name, null)?.let(Base64.getDecoder()::decode) ?: return null
        if (blob.size < 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, masterKey,
            GCMParameterSpec(128, blob, 0, 12),
        )
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun getOrCreateKeyStoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return keyGen.generateKey()
    }

    fun save(a: AuthData) {
        putEncrypted("aes", a.aesKey)
        putEncrypted("hmac", a.hmacKey)
        putEncrypted("refresh_priv", a.refreshPrivateKey)
        putEncrypted("refresh_pub", a.refreshPublicKey)
        putEncrypted("tachyon", a.tachyonToken)
        prefs.edit()
            .putLong("tachyon_expiry", a.tachyonExpiryMs)
            .putString("session_id", a.sessionId)
            .putString("pairing_id", a.pairingId)
            .putString("mobile", a.mobileB64)
            .putString("browser", a.browserB64)
            .apply()
    }

    fun load(): AuthData? {
        val tachyon = getEncrypted("tachyon") ?: return null
        return AuthData(
            aesKey = getEncrypted("aes") ?: return null,
            hmacKey = getEncrypted("hmac") ?: return null,
            refreshPrivateKey = getEncrypted("refresh_priv") ?: byteArrayOf(),
            refreshPublicKey = getEncrypted("refresh_pub") ?: byteArrayOf(),
            tachyonToken = tachyon,
            tachyonExpiryMs = prefs.getLong("tachyon_expiry", 0),
            sessionId = prefs.getString("session_id", "") ?: "",
            pairingId = prefs.getString("pairing_id", "") ?: "",
            mobileB64 = prefs.getString("mobile", "") ?: "",
            browserB64 = prefs.getString("browser", "") ?: "",
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Persists Google session cookies (Gaia pairing). Encrypted at rest. */
    fun saveCookies(cookies: String) {
        putEncrypted("gaia_cookies", cookies.toByteArray(Charsets.UTF_8))
    }

    fun loadCookies(): String? =
        getEncrypted("gaia_cookies")?.toString(Charsets.UTF_8)

    companion object {
        private const val KEY_ALIAS = "relay_rcs_master"

        fun decodeDevice(b64: String): DeviceInfo {
            if (b64.isEmpty()) return DeviceInfo()
            val bytes = Base64.getDecoder().decode(b64)
            return com.garrietta.relay.rcs.proto.decodeDevice(bytes)
        }
    }
}
