// EncryptedSharedPreferences/MasterKey were deprecated in security-crypto 1.1.0-alpha07 in favour
// of a Jetpack DataStore-based API that is still in alpha. Suppress until a stable replacement ships.
@file:Suppress("DEPRECATION")

package shelly.local.security

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "shelly_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(deviceId: String, username: String, password: String) {
        prefs.edit {
            putString("${deviceId}_u", username)
            putString("${deviceId}_p", password)
        }
    }

    fun get(deviceId: String): Pair<String, String>? {
        val u = prefs.getString("${deviceId}_u", null) ?: return null
        val p = prefs.getString("${deviceId}_p", null) ?: return null
        return u to p
    }

    fun delete(deviceId: String) {
        prefs.edit {
            remove("${deviceId}_u")
            remove("${deviceId}_p")
        }
    }
}
