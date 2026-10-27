package com.maintiflow.pro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.maintiflow.pro.data.api.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Jeton de session et préférences, chiffrés sur l'appareil (Android Keystore).
 * Aucun secret serveur n'est embarqué dans l'APK : l'adresse du serveur est saisie à l'installation.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "maintiflow_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val _user = MutableStateFlow(loadUser())
    val user: StateFlow<UserDto?> = _user

    private val _darkMode = MutableStateFlow(prefs.getBoolean(KEY_DARK, false))
    val darkMode: StateFlow<Boolean> = _darkMode

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER, normalize(value)).apply()

    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    fun saveSession(token: String, user: UserDto) {
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_USER, json.encodeToString(user)).apply()
        _user.value = user
    }

    fun updateUser(user: UserDto) {
        prefs.edit().putString(KEY_USER, json.encodeToString(user)).apply()
        _user.value = user
    }

    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
        _user.value = null
    }

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK, enabled).apply()
        _darkMode.value = enabled
    }

    private fun loadUser(): UserDto? = prefs.getString(KEY_USER, null)?.let {
        runCatching { json.decodeFromString<UserDto>(it) }.getOrNull()
    }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user"
        private const val KEY_DARK = "dark_mode"

        /** https://serveur:8443 -> https://serveur:8443/api/v1/ */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (!u.endsWith("/api/v1")) u += "/api/v1"
            return "$u/"
        }
    }
}
