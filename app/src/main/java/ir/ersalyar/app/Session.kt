package ir.ersalyar.app

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets

/** Stores authentication material encrypted at rest. This is protection against casual
 * backup/file extraction; a rooted/fully compromised device can still expose runtime data. */
class Session(context: Context) {
    private val prefs = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ersalyar_secure_session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fail closed for auth data rather than silently writing a plaintext token.
        null
    }

    var supportPhone: String = "09372544666"
    var token: String?
        get() = prefs?.getString("token", null)
        set(value) {
            if (prefs != null) {
                if (value == null) prefs.edit().remove("token").apply()
                else prefs.edit().putString("token", value).apply()
            }
        }

    var subscriptionEnd: String?
        get() = prefs?.getString("subscription_end", null)
        set(value) { prefs?.edit()?.putString("subscription_end", value ?: "")?.apply() }

    var subscriptionActive: Boolean
        get() = prefs?.getBoolean("subscription_active", false) ?: false
        set(value) { prefs?.edit()?.putBoolean("subscription_active", value)?.apply() }

    fun clear() { prefs?.edit()?.clear()?.apply() }
}
