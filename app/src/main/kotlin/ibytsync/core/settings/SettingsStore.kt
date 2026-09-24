package ibytsync.core.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ibytsync.core.pipeline.AudioFormatChoice
import ibytsync.core.upload.IBroadcastOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsStore(context: Context) {    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ibytsync_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isLocalSave(): Boolean = prefs.getBoolean("local_save", false)
    fun setLocalSave(v: Boolean) { prefs.edit().putBoolean("local_save", v).apply() }

    fun getDefaultPlaylistId(): String? = prefs.getString("default_playlist_id", null)
    fun getDefaultPlaylistName(): String? = prefs.getString("default_playlist_name", null)
    fun setDefaultPlaylist(id: String?, name: String?) {
        prefs.edit()
            .putString("default_playlist_id", id)
            .putString("default_playlist_name", name)
            .apply()
    }

    fun isSkipDuplicates(): Boolean = prefs.getBoolean("skip_duplicates", true)
    fun setSkipDuplicates(v: Boolean) { prefs.edit().putBoolean("skip_duplicates", v).apply() }

    fun getDefaultAudioFormat(): AudioFormatChoice {
        val str = prefs.getString("default_audio_format", AudioFormatChoice.MP3_192K.name) ?: AudioFormatChoice.MP3_192K.name
        return AudioFormatChoice.fromLabel(str)
    }
    fun setDefaultAudioFormat(format: AudioFormatChoice) {
        prefs.edit().putString("default_audio_format", format.name).apply()
    }

    fun getSyncedSortOrder(): String = prefs.getString("synced_sort_order", "newest") ?: "newest"
    fun setSyncedSortOrder(order: String) {
        prefs.edit().putString("synced_sort_order", order).apply()
    }

    fun getClientId(): String =
        prefs.getString("ib_client_id", "")?.ifEmpty { DEFAULT_CLIENT_ID } ?: DEFAULT_CLIENT_ID
    fun setClientId(v: String) { prefs.edit().putString("ib_client_id", v.trim()).apply() }

    fun getAccessToken(): String = prefs.getString("ib_access_token", "") ?: ""
    fun getRefreshToken(): String = prefs.getString("ib_refresh_token", "") ?: ""
    fun hasRefreshToken(): Boolean = getRefreshToken().isNotBlank()
    fun isLoggedIn(): Boolean = hasRefreshToken() || (getAccessToken().isNotBlank() && !accessTokenExpired())

    fun setTokens(access: String, refresh: String, expiresInSec: Int = 3599) {
        val leeway = 60_000L
        val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L - leeway
        prefs.edit().putString("ib_access_token", access).putString("ib_refresh_token", refresh)
            .putLong("ib_expires_at", expiresAt).apply()
    }
    fun accessTokenExpired(): Boolean {
        val at = getAccessToken()
        if (at.isEmpty()) return true
        return System.currentTimeMillis() >= prefs.getLong("ib_expires_at", 0L)
    }
    fun clearTokens() {
        prefs.edit().remove("ib_access_token").remove("ib_refresh_token").apply()
    }

    fun getAccountId(): String = prefs.getString("ib_account_id", "") ?: ""
    fun setAccountId(v: String) { prefs.edit().putString("ib_account_id", v).apply() }
    fun clearAccountId() { prefs.edit().remove("ib_account_id").apply() }

    suspend fun ensureFreshToken(): Boolean {
        val refresh = getRefreshToken()
        if (refresh.isBlank()) return false
        if (!accessTokenExpired()) return true
        return withContext(Dispatchers.IO) {
            try {
                val tokens = IBroadcastOAuth.refresh(getClientId(), refresh)
                setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn)
                true
            } catch (e: Exception) {
                android.util.Log.e("SettingsStore", "Silent token refresh failed: ${e.message}")
                false
            }
        }
    }

    companion object {
        // Public OAuth client identifier (not a secret) — baked in so users never type it.
        // Never bake a client_secret here; device-code flow for public clients uses none.
        const val DEFAULT_CLIENT_ID = "4f0aac70ab8711f1b50eb49691aa2236"
    }
}
