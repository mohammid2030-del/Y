@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nova.iptv.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.repo.AppSettings
import com.nova.iptv.data.repo.BackupRepository
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.data.repo.TestResult
import com.nova.iptv.work.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SettingsMessage { INTRO_RESET, EXPORTED, IMPORTED, BAD_PASSPHRASE, FILE_ERROR, HISTORY_CLEARED, CACHE_CLEARED, PIN_SAVED, PIN_REMOVED, WRONG_PIN }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val subs: SubscriptionRepository,
    private val settingsRepo: SettingsRepository,
    private val content: ContentRepository,
    private val backup: BackupRepository,
    private val introStore: com.nova.iptv.data.repo.IntroStore,
    private val subtitlePrefs: com.nova.iptv.data.subs.SubtitlePrefs,
) : ViewModel() {
    var subtitleSize by mutableStateOf(subtitlePrefs.size)
        private set

    fun setSubtitleSize(v: Int) {
        subtitlePrefs.size = v
        subtitleSize = v
    }

    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        subs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val testResults = mutableStateMapOf<Long, TestResult>()
    val testing = mutableStateMapOf<Long, Boolean>()
    var message by mutableStateOf<SettingsMessage?>(null)
    var busy by mutableStateOf(false)
        private set

    fun setActive(id: Long) = viewModelScope.launch { settingsRepo.setActiveSubscription(id) }

    fun delete(sub: SubscriptionEntity, onAllRemoved: () -> Unit) = viewModelScope.launch {
        subs.delete(sub)
        if (subs.getAll().isEmpty()) onAllRemoved()
    }

    fun test(sub: SubscriptionEntity) = viewModelScope.launch {
        testing[sub.id] = true
        testResults[sub.id] = subs.test(subs.toInput(sub))
        testing[sub.id] = false
    }

    fun setTheme(mode: String) = viewModelScope.launch { settingsRepo.setTheme(mode) }
    fun setEpgOffset(min: Int) = viewModelScope.launch { settingsRepo.setEpgOffset(min) }
    fun setLowEnd(v: Boolean) = viewModelScope.launch { settingsRepo.setLowEnd(v) }
    fun setBuffer(sec: Int) = viewModelScope.launch { settingsRepo.setBuffer(sec) }
    fun setShowEpg(v: Boolean) = viewModelScope.launch { settingsRepo.setShowEpg(v) }
    fun setIntroSeconds(v: Int) = viewModelScope.launch { settingsRepo.setIntroSeconds(v) }
    fun setAutoNext(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoNext(v) }
    fun setAutoRefresh(v: Boolean) = viewModelScope.launch {
        settingsRepo.setAutoRefresh(v)
        SyncWorker.schedulePeriodic(appContext, v)
    }

    fun refreshAllInBackground() = SyncWorker.syncNow(appContext)

    fun resetIntroTimings() {
        introStore.clear()
        message = SettingsMessage.INTRO_RESET
    }

    fun clearHistory() = viewModelScope.launch {
        content.clearAllHistory()
        message = SettingsMessage.HISTORY_CLEARED
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearImageCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            appContext.imageLoader.memoryCache?.clear()
            appContext.imageLoader.diskCache?.clear()
        }
        message = SettingsMessage.CACHE_CLEARED
    }

    fun setPin(pin: String?) = viewModelScope.launch {
        settingsRepo.setPin(pin)
        message = if (pin.isNullOrEmpty()) SettingsMessage.PIN_REMOVED else SettingsMessage.PIN_SAVED
    }

    fun verifyPin(pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = settingsRepo.verifyPin(pin)
        if (!ok) message = SettingsMessage.WRONG_PIN
        onResult(ok)
    }

    fun exportTo(uri: Uri, passphrase: String) = viewModelScope.launch {
        busy = true
        try {
            val bytes = backup.exportData(passphrase)
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            }
            message = SettingsMessage.EXPORTED
        } catch (e: Exception) {
            message = SettingsMessage.FILE_ERROR
        } finally {
            busy = false
        }
    }

    fun importFrom(uri: Uri, passphrase: String) = viewModelScope.launch {
        busy = true
        try {
            val bytes = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("no stream")
            }
            backup.restoreData(bytes, passphrase)
            SyncWorker.syncNow(appContext)
            message = SettingsMessage.IMPORTED
        } catch (e: IllegalArgumentException) {
            message = SettingsMessage.BAD_PASSPHRASE
        } catch (e: Exception) {
            message = SettingsMessage.FILE_ERROR
        } finally {
            busy = false
        }
    }
}

@HiltViewModel
class HiddenCategoriesViewModel @Inject constructor(
    private val content: ContentRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val subId: StateFlow<Long> = settingsRepo.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val categories: StateFlow<List<CategoryEntity>> = subId
        .flatMapLatest { content.allCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    var unlocked by mutableStateOf(false)
        private set
    var wrongPin by mutableStateOf(false)
        private set

    fun unlock(pin: String) = viewModelScope.launch {
        val ok = settingsRepo.verifyPin(pin)
        unlocked = ok
        wrongPin = !ok
    }

    fun setHidden(c: CategoryEntity, hidden: Boolean) = viewModelScope.launch { content.setCategoryHidden(c.id, hidden) }
}
