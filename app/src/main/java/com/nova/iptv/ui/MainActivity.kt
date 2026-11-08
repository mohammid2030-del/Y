package com.nova.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nova.iptv.data.repo.AppSettings
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.ui.theme.NovaTheme
import com.nova.iptv.ui.theme.NovaUiFlags
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tv = isTvDevice(this)
        setContent { NovaRoot(isTv = tv) }
    }
}

fun isTvDevice(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val subs: SubscriptionRepository,
    private val account: com.nova.iptv.data.account.AccountRepository,
) : ViewModel() {
    /** Offer Google sign-in before the first subscription (when this build has it set up). */
    val googleFirst: Boolean get() = account.available && !account.signedIn

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _start = MutableStateFlow<String?>(null)
    val start: StateFlow<String?> = _start

    init {
        viewModelScope.launch {
            val s = settingsRepo.current()
            if (account.signedIn) runCatching { kotlinx.coroutines.withTimeoutOrNull(6000) { account.pull() } }
            val hasSubs = subs.getAll().isNotEmpty()
            _start.value = when {
                !s.onboardingDone -> Routes.WELCOME
                !hasSubs && googleFirst -> Routes.SIGNIN
                !hasSubs -> Routes.ADD
                else -> Routes.PROFILES
            }
        }
    }
}

@Composable
fun NovaRoot(isTv: Boolean, vm: MainViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val start by vm.start.collectAsStateWithLifecycle()
    val s = settings
    if (s == null || start == null) {
        Box(Modifier.fillMaxSize()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF07070B)) {} }
        return
    }
    NovaTheme(themeMode = s.themeMode, flags = NovaUiFlags(lowEnd = s.lowEndMode, isTv = isTv)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NovaNavHost(startDestination = start!!, googleFirst = vm.googleFirst)
        }
    }
}
