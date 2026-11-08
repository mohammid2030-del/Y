package com.nova.iptv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nova.iptv.R
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.viewers.Viewer
import com.nova.iptv.data.viewers.ViewersRepository
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Same six palettes as the Apple and Samsung apps — the colour index syncs. */
val ViewerPalettes = listOf(
    listOf(Color(0xFF4F7BFF), Color(0xFF9B6CFF)),
    listOf(Color(0xFFFF5E8A), Color(0xFFFF9F5A)),
    listOf(Color(0xFF16C7A8), Color(0xFF2D8CFF)),
    listOf(Color(0xFFFFB648), Color(0xFFFF5E5E)),
    listOf(Color(0xFFB45CFF), Color(0xFFFF5CC8)),
    listOf(Color(0xFF33CC66), Color(0xFF1A8066)),
)
fun viewerColors(i: Int) = ViewerPalettes[((i % ViewerPalettes.size) + ViewerPalettes.size) % ViewerPalettes.size]

@HiltViewModel
class ViewersViewModel @Inject constructor(
    private val repo: ViewersRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    var subId by mutableStateOf(0L)
        private set
    var list by mutableStateOf<List<Viewer>>(emptyList())
        private set
    var ready by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            subId = settings.current().activeSubscriptionId
            list = repo.visible(subId)
            runCatching { if (repo.pullViewers(subId)) list = repo.visible(subId) }
            ready = true
        }
    }

    /** True when there's nothing to choose (one viewer, no PIN). */
    fun nothingToPick() = list.size == 1 && !list[0].hasPin

    fun refresh() { list = repo.visible(subId) }

    suspend fun check(v: Viewer, pin: String) = repo.checkPin(subId, v, pin)

    fun choose(v: Viewer) = repo.select(subId, v.id)

    fun save(id: String?, name: String, color: Int, pin: String?, clearPin: Boolean) = viewModelScope.launch {
        repo.save(subId, id, name.trim().ifEmpty { "?" }.take(20), color, pin, clearPin); refresh()
    }

    fun delete(v: Viewer) = viewModelScope.launch { repo.delete(subId, v); refresh() }
}

/** "Who's watching?" inside a subscription: up to five viewers, each with an optional 4-digit PIN. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewersScreen(onChosen: () -> Unit, onBack: (() -> Unit)?, manage: Boolean = false, vm: ViewersViewModel = hiltViewModel()) {
    val tv = LocalNovaFlags.current.isTv
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(manage) }
    var pinFor by remember { mutableStateOf<Viewer?>(null) }
    var editor by remember { mutableStateOf<Viewer?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(vm.ready) {
        if (vm.ready && !manage && vm.nothingToPick()) { vm.choose(vm.list[0]); onChosen() }
        else if (vm.ready && tv) { delay(300); runCatching { first.requestFocus() } }
    }
    if (!vm.ready || (!manage && vm.nothingToPick())) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050509)))
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050509))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(if (editing) R.string.viewers_manage else R.string.who_is_watching), color = Color.White,
                fontSize = if (tv) 40.sp else 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (tv) 32.dp else 20.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth(),
            ) {
                vm.list.forEachIndexed { i, v ->
                    ViewerTile(v, editing, tv, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        when {
                            editing -> editor = v
                            v.hasPin -> pinFor = v
                            else -> { vm.choose(v); onChosen() }
                        }
                    }
                }
                if (vm.list.size < 5) AddViewerTile(tv) { adding = true }
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!manage) OutlinedButton(onClick = { editing = !editing }, modifier = Modifier.novaFocus(RoundedCornerShape(50))) {
                    Text(stringResource(if (editing) R.string.done else R.string.viewers_manage))
                }
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.novaFocus(RoundedCornerShape(50))) {
                    Text(stringResource(if (manage) R.string.done else R.string.choose_subscription))
                }
            }
        }
    }

    pinFor?.let { v ->
        PinDialog(title = v.name, onDismiss = { pinFor = null }) { pin, fail ->
            scope.launch {
                if (vm.check(v, pin)) { pinFor = null; vm.choose(v); onChosen() } else fail()
            }
        }
    }
    if (adding || editor != null) {
        ViewerEditor(
            viewer = editor, canDelete = editor != null && vm.list.size > 1,
            onDismiss = { adding = false; editor = null },
            onSave = { name, color, pin, clear -> vm.save(editor?.id, name, color, pin, clear); adding = false; editor = null },
            onDelete = { editor?.let { vm.delete(it) }; editor = null },
        )
    }
}

@Composable
private fun ViewerTile(v: Viewer, editing: Boolean, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val size = if (tv) 132.dp else 104.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 16.dp)) {
        Box(
            modifier.size(size).novaFocus(RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(viewerColors(v.color))).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(v.name.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black)
            if (editing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            if (v.hasPin) Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(v.name, color = Color.White, fontSize = if (tv) 20.sp else 16.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddViewerTile(tv: Boolean, onClick: () -> Unit) {
    val size = if (tv) 132.dp else 104.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 16.dp)) {
        Box(
            Modifier.size(size).novaFocus(RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp))
                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(30.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.viewer_add), color = Color.White.copy(alpha = 0.7f), fontSize = if (tv) 20.sp else 16.sp)
    }
}

/** 4-digit PIN pad that works with a remote (focusable keys) and touch. */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String, fail: () -> Unit) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }
    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d; wrong = false
        if (pin.length == 4) onPin(pin) { wrong = true; pin = "" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (wrong) R.string.wrong_pin else R.string.enter_pin), color = if (wrong) MaterialTheme.colorScheme.error else Color.Unspecified)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { i ->
                        Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)))
                    }
                }
                Spacer(Modifier.height(14.dp))
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<")).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { c, k ->
                            if (k.isEmpty()) Spacer(Modifier.size(64.dp, 52.dp))
                            else Box(
                                Modifier.size(64.dp, 52.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)
                                    .novaFocus(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                                    .background(Color.Gray.copy(alpha = 0.18f))
                                    .clickable { if (k == "<") { pin = pin.dropLast(1) } else press(k) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (k == "<") Icon(Icons.Filled.Backspace, null) else Text(k, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerEditor(
    viewer: Viewer?, canDelete: Boolean, onDismiss: () -> Unit,
    onSave: (name: String, color: Int, pin: String?, clearPin: Boolean) -> Unit, onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(viewer?.name.orEmpty()) }
    var color by remember { mutableIntStateOf(viewer?.color ?: (1..5).random()) }
    var pin by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (viewer == null) R.string.viewer_add else R.string.viewer_edit)) },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                OutlinedTextField(name, { name = it.take(20) }, label = { Text(stringResource(R.string.viewer_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewerPalettes.indices.forEach { i ->
                        Box(
                            Modifier.size(40.dp).novaFocus(CircleShape).clip(CircleShape).background(Brush.linearGradient(viewerColors(i)))
                                .border(if (color == i) 3.dp else 0.dp, Color.White, CircleShape).clickable { color = i },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pin, { v -> pin = v.filter { it.isDigit() }.take(4); clear = false },
                    label = { Text(stringResource(if (viewer?.hasPin == true) R.string.viewer_pin_change else R.string.viewer_pin_optional)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
                if (viewer?.hasPin == true) TextButton(onClick = { clear = true; pin = "" }) {
                    Text(stringResource(if (clear) R.string.viewer_pin_removed else R.string.viewer_pin_remove))
                }
                if (canDelete) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.viewer_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, color, pin.takeIf { it.length == 4 }, clear) }, enabled = name.isNotBlank() && (pin.isEmpty() || pin.length == 4)) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.viewer_delete_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.viewer_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
