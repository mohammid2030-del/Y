@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nova.iptv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nova.iptv.R
import com.nova.iptv.data.sports.ALL_COMPETITIONS
import com.nova.iptv.data.sports.Competition
import com.nova.iptv.data.sports.FavTeam
import com.nova.iptv.data.sports.MatchPrefs
import com.nova.iptv.data.sports.MatchPrefsState
import com.nova.iptv.data.sports.MatchesRepository
import com.nova.iptv.data.sports.BroadcastResolver
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.NovaGradient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MatchPrefsViewModel @Inject constructor(
    private val prefs: MatchPrefs,
    private val repo: MatchesRepository,
    resolver: BroadcastResolver,
) : ViewModel() {
    val state: StateFlow<MatchPrefsState> = prefs.state
    val arabic = resolver.isArabicUi()

    var pickerSlug by mutableStateOf<String?>(null)
    var teams by mutableStateOf<List<FavTeam>>(emptyList())
        private set
    var loadingTeams by mutableStateOf(false)
        private set
    var teamsError by mutableStateOf(false)
        private set

    fun openPicker() = loadTeams(pickerSlug ?: "ksa.1")
    fun closePicker() { pickerSlug = null }

    fun loadTeams(slug: String) {
        pickerSlug = slug
        viewModelScope.launch {
            loadingTeams = true
            teamsError = false
            teams = runCatching { repo.teams(slug) }.getOrElse { teamsError = true; emptyList() }
            loadingTeams = false
        }
    }

    fun toggleCompetition(slug: String) = prefs.toggleCompetition(slug)
    fun toggleTeam(t: FavTeam) = prefs.toggleTeam(t)
    fun setOnlyFavorites(v: Boolean) = prefs.setOnlyFavorites(v)
}

private fun Competition.label(arabic: Boolean) = if (arabic) ar else en

@Composable
fun MatchPrefsScreen(onBack: () -> Unit, vm: MatchPrefsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customize_matches)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.only_my_teams)) },
                    supportingContent = { Text(stringResource(R.string.only_my_teams_desc)) },
                    trailingContent = { Switch(checked = s.onlyFavorites, onCheckedChange = vm::setOnlyFavorites) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.novaFocus(RoundedCornerShape(10.dp)).clickable { vm.setOnlyFavorites(!s.onlyFavorites) },
                )
            }
            item { Header(stringResource(R.string.my_teams)) }
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.teams.forEach { t -> TeamChip(t) { vm.toggleTeam(t) } }
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier.novaFocus(shape).clip(shape).background(NovaGradient).clickable { vm.openPicker() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_team), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Header(stringResource(R.string.club_competitions)) }
            item { CompetitionChips(ALL_COMPETITIONS.filter { !it.national }, s.competitions, vm.arabic, vm::toggleCompetition) }
            item { Header(stringResource(R.string.national_competitions)) }
            item { CompetitionChips(ALL_COMPETITIONS.filter { it.national }, s.competitions, vm.arabic, vm::toggleCompetition) }
        }
    }
    if (vm.pickerSlug != null) TeamPicker(vm, s)
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun TeamChip(t: FavTeam, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.novaFocus(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onRemove).padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White).padding(3.dp)) {
            NetImage(t.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer)
        }
        Spacer(Modifier.width(8.dp))
        Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CompetitionChips(list: List<Competition>, selected: Set<String>, arabic: Boolean, onToggle: (String) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        list.forEach { c ->
            val on = c.slug in selected
            FilterChip(
                selected = on,
                onClick = { onToggle(c.slug) },
                label = { Text(c.label(arabic)) },
                leadingIcon = if (on) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
                modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun TeamPicker(vm: MatchPrefsViewModel, s: MatchPrefsState) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    LaunchedEffect(vm.pickerSlug) { query = "" }
    ModalBottomSheet(onDismissRequest = vm::closePicker, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
            Text(
                stringResource(R.string.add_team),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ALL_COMPETITIONS.filter { it.slug !in setOf("uefa.champions", "uefa.europa", "uefa.europa.conf", "afc.champions", "eng.fa", "esp.copa_del_rey") }, key = { it.slug }) { c ->
                    FilterChip(
                        selected = vm.pickerSlug == c.slug,
                        onClick = { vm.loadTeams(c.slug) },
                        label = { Text(c.label(vm.arabic), maxLines = 1) },
                        modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_team)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val favIds = s.teams.map { it.id }.toSet()
            val shown = vm.teams.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            when {
                vm.loadingTeams -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                vm.teamsError || shown.isEmpty() -> Text(
                    stringResource(if (vm.teamsError) R.string.teams_error else R.string.empty_content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                ) {
                    items(shown, key = { it.id }) { t ->
                        val on = t.id in favIds
                        val shape = RoundedCornerShape(16.dp)
                        Column(
                            Modifier.novaFocus(shape).clip(shape)
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .border(if (on) 1.5.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                                .clickable { vm.toggleTeam(t) }
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box {
                                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).padding(6.dp)) {
                                    NetImage(t.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer)
                                }
                                if (on) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
