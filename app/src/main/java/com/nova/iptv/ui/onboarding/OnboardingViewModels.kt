package com.nova.iptv.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionInput
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.data.repo.SyncProgress
import com.nova.iptv.data.repo.TestResult
import com.nova.iptv.data.toNovaException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    fun finishOnboarding(then: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingDone()
            then()
        }
    }
}

@HiltViewModel
class AddSubscriptionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val subs: SubscriptionRepository,
    private val settings: SettingsRepository,
    private val account: com.nova.iptv.data.account.AccountRepository,
) : ViewModel() {
    val editId: Long = savedState.get<Long>("id") ?: 0L

    var input by mutableStateOf(SubscriptionInput())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<NovaException?>(null)
        private set
    var testResult by mutableStateOf<TestResult?>(null)
        private set

    init {
        if (editId != 0L) {
            viewModelScope.launch {
                subs.get(editId)?.let { input = subs.toInput(it) }
            }
        }
    }

    fun update(transform: (SubscriptionInput) -> SubscriptionInput) {
        input = transform(input)
        error = null
        testResult = null
    }

    private fun validate(): Boolean {
        val i = input
        val ok = when (i.type) {
            SourceType.XTREAM -> i.server.isNotBlank() && i.username.isNotBlank() && i.password.isNotBlank()
            SourceType.M3U_URL -> i.m3uUrl.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
            else -> i.fileUri.isNotBlank()
        }
        if (!ok) error = NovaException(ErrorKind.INVALID_URL)
        return ok
    }

    fun test() {
        if (!validate() || busy) return
        viewModelScope.launch {
            busy = true
            val r = subs.test(input)
            testResult = r
            error = r.error
            busy = false
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        if (!validate() || busy) return
        viewModelScope.launch {
            busy = true
            error = null
            val r = subs.test(input)
            testResult = r
            if (r.ok) {
                val id = subs.save(input.copy(id = editId))
                account.pushSub(id)
                settings.setOnboardingDone()
                busy = false
                onSaved(id)
            } else {
                error = r.error
                busy = false
            }
        }
    }
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val subs: SubscriptionRepository,
    private val settings: SettingsRepository,
    private val content: ContentRepository,
) : ViewModel() {
    val subId: Long = savedState.get<Long>("id") ?: 0L

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress

    var error by mutableStateOf<NovaException?>(null)
        private set
    var done by mutableStateOf(false)
        private set
    var hasCachedContent by mutableStateOf(false)
        private set

    init {
        start()
    }

    fun start() {
        error = null
        viewModelScope.launch {
            try {
                subs.sync(subId) { p -> _progress.value = p }
                settings.setActiveSubscription(subId)
                done = true
            } catch (e: Exception) {
                error = e.toNovaException()
                hasCachedContent = content.all(subId, ContentType.LIVE, 1).first().isNotEmpty() ||
                    content.all(subId, ContentType.MOVIE, 1).first().isNotEmpty()
            }
        }
    }

    fun continueOffline(then: () -> Unit) {
        viewModelScope.launch {
            settings.setActiveSubscription(subId)
            then()
        }
    }
}
