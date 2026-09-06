package ir.ersalyar.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.UUID
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Client-side anti-tamper checks. Never treat these checks as server authorization. */
object DeviceSecurity {
    private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    fun installationId(): String {
        val c = appContext ?: error("DeviceSecurity.init() must be called first")
        val prefs = securePrefs(c)
        val old = prefs.getString("install_id", null)
        if (old != null) return old
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", id).apply()
        return id
    }

    fun isDebuggable(): Boolean = appContext?.applicationInfo?.flags?.and(2) != 0

    /** Returns true when an expected signing certificate was configured and matches. */
    fun signingCertificateMatches(): Boolean {
        val expected = BuildConfig.EXPECTED_SIGNING_CERT_SHA256.trim().replace(":", "").uppercase()
        if (expected.isBlank()) return true // Deliberately configurable; enforce in production CI.
        val c = appContext ?: return false
        return try {
            val pm = c.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            signatures.any { cert ->
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                digest.joinToString("") { "%02X".format(it) } == expected
            }
        } catch (_: Exception) { false }
    }

    fun releaseIntegrityOk(): Boolean = !BuildConfig.DEBUG && signingCertificateMatches() || BuildConfig.DEBUG

    private fun securePrefs(c: Context) = try {
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(c, "ersalyar_device_identity", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { throw IllegalStateException("Secure device storage unavailable", e) }
}
