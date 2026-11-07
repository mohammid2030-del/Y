@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.R
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.repo.SyncStage
import com.nova.iptv.ui.components.errorText
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaBlack
import com.nova.iptv.ui.theme.NovaBlue
import com.nova.iptv.ui.theme.NovaGradient
import com.nova.iptv.ui.theme.NovaViolet
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun NovaLogo(size: Int = 96) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(NovaGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val lowEnd = LocalNovaFlags.current.lowEnd
    val anim = remember { Animatable(if (lowEnd) 1f else 0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(900)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(NovaViolet.copy(alpha = 0.35f), NovaBlue.copy(alpha = 0.12f), NovaBlack),
                    radius = 1400f,
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).alpha(anim.value).scale(0.9f + 0.1f * anim.value),
        ) {
            NovaLogo(112)
            Spacer(Modifier.height(28.dp))
            Text(
                "NOVA IPTV",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_tagline),
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.widthIn(min = 220.dp).height(52.dp).novaFocus(RoundedCornerShape(26.dp)),
            ) {
                Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun LanguageScreen(onContinue: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    var selected by remember { mutableStateOf(if (current.isEmpty()) "" else current.substringBefore(',').substringBefore('-')) }
    val options = listOf(
        "ar" to stringResource(R.string.lang_arabic),
        "en" to stringResource(R.string.lang_english),
        "" to stringResource(R.string.lang_system),
    )
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.choose_language), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            options.forEach { (tag, label) ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected == tag) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .novaFocus(RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            selected = tag
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                else LocaleListCompat.forLanguageTags(tag)
                            )
                        },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == tag, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.finishOnboarding(onContinue) },
            modifier = Modifier.widthIn(min = 220.dp).height(50.dp).novaFocus(RoundedCornerShape(25.dp)),
        ) { Text(stringResource(R.string.continue_label)) }
    }
}

@Composable
fun AddSubscriptionScreen(
    onSaved: (Long) -> Unit,
    onBack: (() -> Unit)?,
    vm: AddSubscriptionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val input = vm.input
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val types = listOf(SourceType.XTREAM, SourceType.M3U_URL, SourceType.M3U_FILE)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.update { it.copy(fileUri = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.editId != 0L) R.string.edit_subscription else R.string.add_subscription)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                TabRow(selectedTabIndex = types.indexOf(input.type).coerceAtLeast(0)) {
                    types.forEach { t ->
                        Tab(
                            selected = input.type == t,
                            onClick = { vm.update { it.copy(type = t) } },
                            text = {
                                Text(
                                    stringResource(
                                        when (t) {
                                            SourceType.XTREAM -> R.string.type_xtream
                                            SourceType.M3U_URL -> R.string.type_m3u
                                            else -> R.string.type_file
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = input.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                when (input.type) {
                    SourceType.XTREAM -> {
                        OutlinedTextField(
                            value = input.server,
                            onValueChange = { v -> vm.update { it.copy(server = v) } },
                            label = { Text(stringResource(R.string.field_server)) },
                            placeholder = { Text("http://example.com:8080") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = input.username,
                            onValueChange = { v -> vm.update { it.copy(username = v) } },
                            label = { Text(stringResource(R.string.field_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = input.password,
                            onValueChange = { v -> vm.update { it.copy(password = v) } },
                            label = { Text(stringResource(R.string.field_password)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SourceType.M3U_URL -> {
                        OutlinedTextField(
                            value = input.m3uUrl,
                            onValueChange = { v -> vm.update { it.copy(m3uUrl = v) } },
                            label = { Text(stringResource(R.string.field_m3u_url)) },
                            placeholder = { Text("https://…/playlist.m3u8") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().height(56.dp).novaFocus(),
                        ) {
                            Icon(Icons.Filled.FolderOpen, null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (input.fileUri.isBlank()) stringResource(R.string.pick_file)
                                else Uri.parse(input.fileUri).lastPathSegment?.substringAfterLast('/') ?: input.fileUri,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.novaFocus()) {
                    Text(stringResource(if (showAdvanced) R.string.hide_advanced else R.string.show_advanced))
                }
                AnimatedVisibility(showAdvanced) {
                    Column {
                        OutlinedTextField(
                            value = input.epgUrl,
                            onValueChange = { v -> vm.update { it.copy(epgUrl = v) } },
                            label = { Text(stringResource(R.string.field_epg_url)) },
                            supportingText = { Text(stringResource(R.string.field_epg_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = input.userAgent,
                            onValueChange = { v -> vm.update { it.copy(userAgent = v) } },
                            label = { Text(stringResource(R.string.field_user_agent)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (input.type == SourceType.XTREAM) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.live_format), style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = input.liveFormat == "ts",
                                    onClick = { vm.update { it.copy(liveFormat = "ts") } },
                                    label = { Text("MPEG-TS") },
                                )
                                FilterChip(
                                    selected = input.liveFormat == "m3u8",
                                    onClick = { vm.update { it.copy(liveFormat = "m3u8") } },
                                    label = { Text("HLS (m3u8)") },
                                )
                            }
                        }
                    }
                }

                vm.testResult?.let { r ->
                    if (r.ok) {
                        Spacer(Modifier.height(12.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text(stringResource(R.string.connection_ok), fontWeight = FontWeight.SemiBold)
                                    r.auth?.let { a ->
                                        if (a.expiresAtMs > 0) {
                                            Text(
                                                stringResource(
                                                    R.string.expires_on,
                                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.expiresAtMs)),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (a.maxConnections > 0) {
                                            Text(
                                                stringResource(R.string.max_connections, a.maxConnections),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                vm.error?.let { e ->
                    Spacer(Modifier.height(12.dp))
                    Text(errorText(e), color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.test() },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f).height(52.dp).novaFocus(),
                    ) { Text(stringResource(R.string.test_connection)) }
                    Button(
                        onClick = { vm.save(onSaved) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f).height(52.dp).novaFocus(),
                    ) {
                        if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text(stringResource(R.string.save_and_load))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.legal_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SyncScreen(onDone: () -> Unit, onEdit: (Long) -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    val nf = remember { NumberFormat.getIntegerInstance() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NovaLogo(84)
        Spacer(Modifier.height(28.dp))
        val err = vm.error
        if (err == null) {
            val p = progress
            Text(
                stringResource(
                    when (p?.stage) {
                        SyncStage.CATEGORIES -> R.string.stage_categories
                        SyncStage.CONTENT -> R.string.stage_content
                        SyncStage.LIVE -> R.string.stage_live
                        SyncStage.MOVIES -> R.string.stage_movies
                        SyncStage.SERIES -> R.string.stage_series
                        SyncStage.PLAYLIST -> R.string.stage_playlist
                        SyncStage.EPG -> R.string.stage_epg
                        SyncStage.SAVING -> R.string.stage_saving
                        SyncStage.DONE -> R.string.stage_done
                        else -> R.string.stage_connecting
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { (p?.percent ?: 0) / 100f },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${p?.percent ?: 0}%" + if ((p?.count ?: 0) > 0) "  •  " + stringResource(R.string.items_loaded, nf.format(p!!.count)) else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.sync_failed), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(errorText(err), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.start() }, modifier = Modifier.widthIn(min = 200.dp).novaFocus()) {
                Text(stringResource(R.string.retry))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onEdit(vm.subId) }, modifier = Modifier.widthIn(min = 200.dp).novaFocus()) {
                Text(stringResource(R.string.edit_subscription))
            }
            if (vm.hasCachedContent) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.continueOffline(onDone) }, modifier = Modifier.novaFocus()) {
                    Text(stringResource(R.string.continue_cached))
                }
            }
        }
    }
}
