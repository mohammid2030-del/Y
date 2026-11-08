@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.settings

import kotlinx.coroutines.launch
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.BuildConfig
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.ui.components.errorText
import com.nova.iptv.ui.components.novaFocus
import java.text.DateFormat
import java.util.Date

private data class Choice<T>(val value: T, val label: String)

@Composable
private fun <T> ChoiceDialog(title: String, choices: List<Choice<T>>, current: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(choices) { c ->
                    Row(
                        Modifier.fillMaxWidth().novaFocus(RoundedCornerShape(8.dp)).clickable { onPick(c.value); onDismiss() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.value == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(c.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun SecretDialog(
    title: String,
    label: String,
    numeric: Boolean,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    minLength: Int = 4,
    error: String? = null,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = if (numeric) v.filter { it.isDigit() }.take(8) else v },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.length >= minLength) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.novaFocus(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier,
    )
}

private enum class PendingAction { EXPORT, IMPORT }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSync: (Long) -> Unit,
    onHiddenCategories: () -> Unit,
    onAllRemoved: () -> Unit,
    onMatchPrefs: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onViewers: () -> Unit = {},
    onSwitchViewer: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val accountEmail by vm.accountEmail.collectAsStateWithLifecycle()
    val syncedText = stringResource(R.string.synced)
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var themeDialog by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    var bufferDialog by remember { mutableStateOf(false) }
    var offsetDialog by remember { mutableStateOf(false) }
    var introDialog by remember { mutableStateOf(false) }
    var subsSizeDialog by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var tmdbDialog by remember { mutableStateOf(false) }
    val tmdbOn by vm.tmdbEnabled.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var removePinDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { pendingUri = uri; pendingAction = PendingAction.EXPORT }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingUri = uri; pendingAction = PendingAction.IMPORT }
    }

    val messageText = when (vm.message) {
        SettingsMessage.INTRO_RESET -> stringResource(R.string.msg_intro_reset)
        SettingsMessage.EXPORTED -> stringResource(R.string.msg_exported)
        SettingsMessage.IMPORTED -> stringResource(R.string.msg_imported)
        SettingsMessage.BAD_PASSPHRASE -> stringResource(R.string.msg_bad_passphrase)
        SettingsMessage.FILE_ERROR -> stringResource(R.string.err_file)
        SettingsMessage.HISTORY_CLEARED -> stringResource(R.string.msg_history_cleared)
        SettingsMessage.CACHE_CLEARED -> stringResource(R.string.msg_cache_cleared)
        SettingsMessage.PIN_SAVED -> stringResource(R.string.msg_pin_saved)
        SettingsMessage.PIN_REMOVED -> stringResource(R.string.msg_pin_removed)
        SettingsMessage.WRONG_PIN -> stringResource(R.string.wrong_pin)
        SettingsMessage.TMDB_SAVED -> stringResource(R.string.tmdb_saved)
        null -> null
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.message = null
        }
    }

    val themeLabels = mapOf(
        "dark" to stringResource(R.string.theme_dark),
        "light" to stringResource(R.string.theme_light),
        "system" to stringResource(R.string.theme_system),
    )
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-')
    val langLabels = mapOf(
        "ar" to stringResource(R.string.lang_arabic),
        "en" to stringResource(R.string.lang_english),
        "" to stringResource(R.string.lang_system),
    )
    val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (vm.accountAvailable) {
                item { SectionHeader(stringResource(R.string.account)) }
                item {
                    val mail = accountEmail
                    if (mail == null) SettingRow(stringResource(R.string.sign_in_google), stringResource(R.string.account_none), onClick = onSignIn)
                    else {
                        SettingRow(stringResource(R.string.signed_in_as, mail), stringResource(R.string.google_why))
                        SettingRow(stringResource(R.string.sign_out), onClick = { vm.signOut() })
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.viewers)) }
            item {
                SettingRow(stringResource(R.string.switch_viewer), onClick = onSwitchViewer)
                SettingRow(stringResource(R.string.viewers_manage), onClick = onViewers)
                SettingRow(
                    stringResource(R.string.sync_now),
                    onClick = if (vm.syncing) null else ({ vm.syncNow { scopeSnack(snackbar, syncedText) } }),
                    trailing = if (vm.syncing) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
                )
            }
            item { SectionHeader(stringResource(R.string.subscriptions)) }
            items(subs, key = { it.id }) { sub ->
                val active = sub.id == s.activeSubscriptionId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)).clickable { vm.setActive(sub.id) },
                        ) {
                            RadioButton(selected = active, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sub.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (sub.type) {
                                        SourceType.XTREAM -> stringResource(R.string.type_xtream)
                                        SourceType.M3U_URL -> stringResource(R.string.type_m3u)
                                        else -> stringResource(R.string.type_file)
                                    } + if (sub.lastSync > 0) " • " + stringResource(R.string.last_update, dateFmt.format(Date(sub.lastSync))) else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (sub.expiresAt > 0) {
                                    Text(
                                        stringResource(R.string.expires_on, DateFormat.getDateInstance().format(Date(sub.expiresAt))),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val tr = vm.testResults[sub.id]
                        if (vm.testing[sub.id] == true) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.testing), style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (tr != null) {
                            Text(
                                if (tr.ok) "✓ " + stringResource(R.string.connection_ok) else "✕ " + errorText(tr.error),
                                color = if (tr.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { vm.test(sub) }) { Icon(Icons.Filled.NetworkCheck, stringResource(R.string.test_connection)) }
                            IconButton(onClick = { onSync(sub.id) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                            IconButton(onClick = { onEdit(sub.id) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_subscription)) }
                            IconButton(onClick = { deleteTarget = sub }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
            item {
                SettingRow(stringResource(R.string.add_subscription), onClick = onAdd, trailing = { Icon(Icons.Filled.Add, null) })
            }
            item {
                SettingRow(
                    stringResource(R.string.auto_refresh), stringResource(R.string.auto_refresh_desc),
                    onClick = { vm.setAutoRefresh(!s.autoRefresh) },
                    trailing = { Switch(checked = s.autoRefresh, onCheckedChange = { vm.setAutoRefresh(it) }) },
                )
            }
            item { SettingRow(stringResource(R.string.refresh_all_now), onClick = { vm.refreshAllInBackground() }) }

            item { SectionHeader(stringResource(R.string.appearance)) }
            item { SettingRow(stringResource(R.string.theme), themeLabels[s.themeMode], onClick = { themeDialog = true }) }
            item { SettingRow(stringResource(R.string.language), langLabels[currentLang] ?: currentLang, onClick = { langDialog = true }) }
            item {
                SettingRow(
                    stringResource(R.string.low_end_mode), stringResource(R.string.low_end_desc),
                    onClick = { vm.setLowEnd(!s.lowEndMode) },
                    trailing = { Switch(checked = s.lowEndMode, onCheckedChange = { vm.setLowEnd(it) }) },
                )
            }

            item { SectionHeader(stringResource(R.string.playback)) }
            item {
                SettingRow(
                    stringResource(R.string.buffer_size), stringResource(R.string.seconds_n, s.bufferSeconds),
                    onClick = { bufferDialog = true },
                )
            }

            item {
                SettingRow(
                    stringResource(R.string.intro_length),
                    stringResource(R.string.intro_source) + " · " +
                        if (s.introSeconds == 0) stringResource(R.string.intro_off) else stringResource(R.string.seconds_n, s.introSeconds),
                    onClick = { introDialog = true },
                )
            }
            item { SettingRow(stringResource(R.string.reset_intro), stringResource(R.string.reset_intro_desc), onClick = { vm.resetIntroTimings() }) }
            item {
                SettingRow(
                    stringResource(R.string.autoplay_next), stringResource(R.string.autoplay_next_desc),
                    onClick = { vm.setAutoNext(!s.autoNext) },
                    trailing = { Switch(checked = s.autoNext, onCheckedChange = { vm.setAutoNext(it) }) },
                )
            }

            item { SectionHeader(stringResource(R.string.tmdb_section)) }
            item {
                SettingRow(
                    stringResource(R.string.tmdb_key),
                    stringResource(if (tmdbOn) R.string.tmdb_key_on else R.string.tmdb_key_off),
                    onClick = { vm.resetTmdbError(); tmdbDialog = true },
                    trailing = if (tmdbOn) { { TextButton(onClick = vm::removeTmdbKey) { Text(stringResource(R.string.remove)) } } } else null,
                )
            }
            if (!tmdbOn) {
                item {
                    SettingRow(
                        stringResource(R.string.tmdb_get_key),
                        "themoviedb.org/settings/api",
                        onClick = { runCatching { uriHandler.openUri("https://www.themoviedb.org/settings/api") } },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.tmdb_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.matches_section)) }
            item {
                SettingRow(
                    stringResource(R.string.show_matches), stringResource(R.string.show_matches_desc),
                    onClick = { vm.setShowMatches(!s.showMatches) },
                    trailing = { Switch(checked = s.showMatches, onCheckedChange = { vm.setShowMatches(it) }) },
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.customize_matches), stringResource(R.string.customize_matches_desc),
                    onClick = onMatchPrefs,
                )
            }

            item { SectionHeader(stringResource(R.string.subtitles_section)) }
            item {
                SettingRow(
                    stringResource(R.string.subs_size),
                    stringResource(listOf(R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_xlarge)[vm.subtitleSize.coerceIn(0, 3)]),
                    onClick = { subsSizeDialog = true },
                )
            }

            item { SectionHeader(stringResource(R.string.tv_guide)) }
            item {
                SettingRow(
                    stringResource(R.string.show_epg_list),
                    onClick = { vm.setShowEpg(!s.showEpgInList) },
                    trailing = { Switch(checked = s.showEpgInList, onCheckedChange = { vm.setShowEpg(it) }) },
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.epg_offset),
                    formatOffset(s.epgOffsetMinutes),
                    onClick = { offsetDialog = true },
                )
            }

            item { SectionHeader(stringResource(R.string.parental_control)) }
            item {
                SettingRow(
                    stringResource(if (s.parentalPinSet) R.string.change_pin else R.string.set_pin),
                    stringResource(R.string.pin_desc),
                    onClick = { pinDialog = true },
                )
            }
            if (s.parentalPinSet) {
                item { SettingRow(stringResource(R.string.remove_pin), onClick = { removePinDialog = true }) }
            }
            item { SettingRow(stringResource(R.string.hidden_categories), stringResource(R.string.hidden_categories_desc), onClick = onHiddenCategories) }

            item { SectionHeader(stringResource(R.string.data_privacy)) }
            item { SettingRow(stringResource(R.string.clear_history), onClick = { vm.clearHistory() }) }
            item { SettingRow(stringResource(R.string.clear_image_cache), onClick = { vm.clearImageCache() }) }
            item {
                SettingRow(
                    stringResource(R.string.export_settings), stringResource(R.string.export_desc),
                    onClick = { exportLauncher.launch("nova-backup.nova") },
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.import_settings),
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
            }

            item { SectionHeader(stringResource(R.string.about)) }
            item {
                SettingRow(
                    "NOVA IPTV " + BuildConfig.VERSION_NAME,
                    stringResource(R.string.about_desc),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (tmdbDialog) {
        SecretDialog(
            title = stringResource(R.string.tmdb_key),
            label = stringResource(R.string.tmdb_key_hint),
            numeric = false,
            confirm = stringResource(if (vm.tmdbChecking) R.string.tmdb_checking else R.string.save),
            onConfirm = { if (!vm.tmdbChecking) vm.saveTmdbKey(it) { tmdbDialog = false } },
            onDismiss = { tmdbDialog = false },
            minLength = 16,
            error = if (vm.tmdbRejected) stringResource(R.string.tmdb_invalid) else null,
        )
    }
    if (themeDialog) {
        ChoiceDialog(
            stringResource(R.string.theme),
            themeLabels.map { (k, v) -> Choice(k, v) },
            s.themeMode,
            onPick = { vm.setTheme(it) },
            onDismiss = { themeDialog = false },
        )
    }
    if (langDialog) {
        ChoiceDialog(
            stringResource(R.string.language),
            langLabels.map { (k, v) -> Choice(k, v) },
            currentLang,
            onPick = { tag ->
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
            },
            onDismiss = { langDialog = false },
        )
    }
    if (bufferDialog) {
        ChoiceDialog(
            stringResource(R.string.buffer_size),
            listOf(10, 20, 30, 60, 120).map { Choice(it, stringResource(R.string.seconds_n, it)) },
            s.bufferSeconds,
            onPick = { vm.setBuffer(it) },
            onDismiss = { bufferDialog = false },
        )
    }
    if (subsSizeDialog) {
        ChoiceDialog(
            stringResource(R.string.subs_size),
            listOf(R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_xlarge)
                .mapIndexed { i, r -> Choice(i, stringResource(r)) },
            vm.subtitleSize,
            onPick = { v -> vm.changeSubtitleSize(v) },
            onDismiss = { subsSizeDialog = false },
        )
    }
    if (introDialog) {
        ChoiceDialog(
            stringResource(R.string.intro_length),
            listOf(0, 45, 60, 75, 85, 90, 120).map {
                Choice(it, if (it == 0) stringResource(R.string.intro_off) else stringResource(R.string.seconds_n, it))
            },
            s.introSeconds,
            onPick = { vm.setIntroSeconds(it) },
            onDismiss = { introDialog = false },
        )
    }
    if (offsetDialog) {
        ChoiceDialog(
            stringResource(R.string.epg_offset),
            (-12 * 60..12 * 60 step 30).map { Choice(it, formatOffset(it)) },
            s.epgOffsetMinutes,
            onPick = { vm.setEpgOffset(it) },
            onDismiss = { offsetDialog = false },
        )
    }
    if (pinDialog) {
        SecretDialog(
            title = stringResource(if (s.parentalPinSet) R.string.change_pin else R.string.set_pin),
            label = stringResource(R.string.new_pin),
            numeric = true,
            confirm = stringResource(R.string.save),
            onConfirm = { vm.setPin(it); pinDialog = false },
            onDismiss = { pinDialog = false },
        )
    }
    if (removePinDialog) {
        SecretDialog(
            title = stringResource(R.string.remove_pin),
            label = stringResource(R.string.current_pin),
            numeric = true,
            confirm = stringResource(R.string.remove),
            onConfirm = { pin ->
                vm.verifyPin(pin) { ok -> if (ok) vm.setPin(null) }
                removePinDialog = false
            },
            onDismiss = { removePinDialog = false },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_subscription_q)) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target, onAllRemoved); deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val action = pendingAction
    val uri = pendingUri
    if (action != null && uri != null) {
        SecretDialog(
            title = stringResource(if (action == PendingAction.EXPORT) R.string.export_settings else R.string.import_settings),
            label = stringResource(R.string.passphrase),
            numeric = false,
            minLength = 6,
            confirm = stringResource(R.string.ok),
            onConfirm = { pass ->
                if (action == PendingAction.EXPORT) vm.exportTo(uri, pass) else vm.importFrom(uri, pass)
                pendingAction = null; pendingUri = null
            },
            onDismiss = { pendingAction = null; pendingUri = null },
        )
    }
}

private fun scopeSnack(host: SnackbarHostState, text: String) {
    kotlinx.coroutines.MainScope().launch { host.showSnackbar(text) }
}

private fun formatOffset(min: Int): String {
    if (min == 0) return "0"
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return "$sign${a / 60}:${(a % 60).toString().padStart(2, '0')}"
}

@Composable
fun HiddenCategoriesScreen(onBack: () -> Unit, vm: HiddenCategoriesViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val locked = settings.parentalPinSet && !vm.unlocked

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hidden_categories)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        if (locked) {
            SecretDialog(
                title = stringResource(R.string.enter_pin),
                label = stringResource(R.string.current_pin),
                numeric = true,
                confirm = stringResource(R.string.ok),
                onConfirm = { vm.unlock(it) },
                onDismiss = onBack,
                error = if (vm.wrongPin) stringResource(R.string.wrong_pin) else null,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.hidden_categories_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            listOf(ContentType.LIVE to R.string.tab_live, ContentType.MOVIE to R.string.tab_movies, ContentType.SERIES to R.string.tab_series)
                .forEach { (type, label) ->
                    val group = categories.filter { it.type == type }
                    if (group.isNotEmpty()) {
                        item(key = "h_$type") { SectionHeader(stringResource(label)) }
                        items(group, key = { it.id }) { c ->
                            SettingRow(
                                c.name,
                                onClick = { vm.setHidden(c, !c.hidden) },
                                trailing = { Switch(checked = c.hidden, onCheckedChange = { vm.setHidden(c, it) }) },
                            )
                        }
                    }
                }
        }
    }
}
