package com.nova.iptv.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val activeSubscriptionId: Long = 0,
    val themeMode: String = "dark",
    val epgOffsetMinutes: Int = 0,
    val lowEndMode: Boolean = false,
    val bufferSeconds: Int = 30,
    val autoRefresh: Boolean = true,
    val parentalPinSet: Boolean = false,
    val lastChannel: String = "",
    val showEpgInList: Boolean = true,
    val introSeconds: Int = 0,
    val autoNext: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val ACTIVE_SUB = longPreferencesKey("active_sub")
        val THEME = stringPreferencesKey("theme")
        val EPG_OFFSET = intPreferencesKey("epg_offset_min")
        val LOW_END = booleanPreferencesKey("low_end")
        val BUFFER = intPreferencesKey("buffer_sec")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val SHOW_EPG = booleanPreferencesKey("show_epg_list")
        val INTRO = intPreferencesKey("intro_sec")
        val AUTO_NEXT = booleanPreferencesKey("auto_next")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            activeSubscriptionId = p[Keys.ACTIVE_SUB] ?: 0L,
            themeMode = p[Keys.THEME] ?: "dark",
            epgOffsetMinutes = p[Keys.EPG_OFFSET] ?: 0,
            lowEndMode = p[Keys.LOW_END] ?: false,
            bufferSeconds = p[Keys.BUFFER] ?: 30,
            autoRefresh = p[Keys.AUTO_REFRESH] ?: true,
            parentalPinSet = !p[Keys.PIN_HASH].isNullOrEmpty(),
            lastChannel = p[Keys.LAST_CHANNEL] ?: "",
            showEpgInList = p[Keys.SHOW_EPG] ?: true,
            introSeconds = p[Keys.INTRO] ?: 0,
            autoNext = p[Keys.AUTO_NEXT] ?: true,
        )
    }.distinctUntilChanged()

    val activeSubscriptionId: Flow<Long> = settings.map { it.activeSubscriptionId }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.ONBOARDING] = true }
    suspend fun setActiveSubscription(id: Long) = context.dataStore.edit { it[Keys.ACTIVE_SUB] = id }
    suspend fun setTheme(mode: String) = context.dataStore.edit { it[Keys.THEME] = mode }
    suspend fun setEpgOffset(min: Int) = context.dataStore.edit { it[Keys.EPG_OFFSET] = min }
    suspend fun setLowEnd(v: Boolean) = context.dataStore.edit { it[Keys.LOW_END] = v }
    suspend fun setBuffer(sec: Int) = context.dataStore.edit { it[Keys.BUFFER] = sec }
    suspend fun setAutoRefresh(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_REFRESH] = v }
    suspend fun setShowEpg(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_EPG] = v }
    suspend fun setIntroSeconds(v: Int) = context.dataStore.edit { it[Keys.INTRO] = v }
    suspend fun setAutoNext(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_NEXT] = v }
    suspend fun setLastChannel(v: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = v }

    suspend fun setPin(pin: String?) = context.dataStore.edit {
        if (pin.isNullOrEmpty()) {
            it.remove(Keys.PIN_HASH); it.remove(Keys.PIN_SALT)
        } else {
            val salt = ByteArray(16).also { b -> SecureRandom().nextBytes(b) }.toHex()
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val h = p[Keys.PIN_HASH] ?: return true
        val salt = p[Keys.PIN_SALT] ?: return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), h.toByteArray())
    }

    /** Raw values for encrypted export (PIN is not exported). */
    suspend fun exportMap(): Map<String, String> {
        val s = current()
        return mapOf(
            "theme" to s.themeMode,
            "epg_offset" to s.epgOffsetMinutes.toString(),
            "low_end" to s.lowEndMode.toString(),
            "buffer" to s.bufferSeconds.toString(),
            "auto_refresh" to s.autoRefresh.toString(),
            "show_epg" to s.showEpgInList.toString(),
        )
    }

    suspend fun importMap(m: Map<String, String>) = context.dataStore.edit { p ->
        m["theme"]?.let { p[Keys.THEME] = it }
        m["epg_offset"]?.toIntOrNull()?.let { p[Keys.EPG_OFFSET] = it }
        m["low_end"]?.toBooleanStrictOrNull()?.let { p[Keys.LOW_END] = it }
        m["buffer"]?.toIntOrNull()?.let { p[Keys.BUFFER] = it }
        m["auto_refresh"]?.toBooleanStrictOrNull()?.let { p[Keys.AUTO_REFRESH] = it }
        m["show_epg"]?.toBooleanStrictOrNull()?.let { p[Keys.SHOW_EPG] = it }
    }

    private fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = (salt + pin).toByteArray()
        repeat(10_000) { bytes = md.digest(bytes) }
        return bytes.toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
