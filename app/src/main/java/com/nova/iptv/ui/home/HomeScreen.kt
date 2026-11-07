@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.R
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.onboarding.NovaLogo
import com.nova.iptv.ui.theme.LocalNovaFlags

enum class HomeTab(@StringRes val label: Int, val icon: ImageVector) {
    LIVE(R.string.tab_live, Icons.Filled.LiveTv),
    MOVIES(R.string.tab_movies, Icons.Filled.Movie),
    SERIES(R.string.tab_series, Icons.Filled.VideoLibrary),
    FAVORITES(R.string.tab_favorites, Icons.Filled.Favorite),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
}

@Composable
fun HomeScreen(
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddSubscription: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = HomeTab.entries[tabIndex]
    val flags = LocalNovaFlags.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720 || flags.isTv
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()

    BackHandler(enabled = tabIndex != 0) { tabIndex = 0 }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            if (activeId == 0L) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(stringResource(R.string.no_subscription), Modifier.weight(1f))
                    Button(onClick = onAddSubscription, modifier = Modifier.padding(24.dp).novaFocus()) {
                        Text(stringResource(R.string.add_subscription))
                    }
                }
            } else {
                when (tab) {
                    HomeTab.LIVE -> LiveTab(wide = wide)
                    HomeTab.MOVIES -> VodTab(vm = hiltViewModel<MoviesViewModel>(), onOpen = onOpenMovie)
                    HomeTab.SERIES -> VodTab(vm = hiltViewModel<SeriesViewModel>(), onOpen = onOpenSeries)
                    HomeTab.FAVORITES -> FavoritesTab(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
                    HomeTab.SEARCH -> SearchTab(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
                }
            }
        }
    }

    val switcher: @Composable () -> Unit = {
        SubscriptionSwitcher(
            names = subs.map { it.id to it.name },
            activeId = activeId,
            onSelect = vm::switchTo,
        )
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                header = {
                    Spacer(Modifier.height(12.dp))
                    NovaLogo(44)
                },
                modifier = Modifier.fillMaxHeight().statusBarsPadding(),
            ) {
                Spacer(Modifier.height(12.dp))
                HomeTab.entries.forEachIndexed { i, t ->
                    NavigationRailItem(
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = false, onClick = onOpenEpg,
                    icon = { Icon(Icons.Filled.CalendarViewDay, null) },
                    label = { Text(stringResource(R.string.tv_guide)) },
                )
                NavigationRailItem(
                    selected = false, onClick = onOpenSettings,
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.settings)) },
                )
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(tab.label),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    switcher()
                }
                content(Modifier.weight(1f))
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NovaLogo(30)
                            Spacer(Modifier.width(10.dp))
                            Text("NOVA", fontWeight = FontWeight.Black)
                        }
                    },
                    actions = {
                        switcher()
                        IconButton(onClick = onOpenEpg) { Icon(Icons.Filled.CalendarViewDay, stringResource(R.string.tv_guide)) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    HomeTab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = i == tabIndex,
                            onClick = { tabIndex = i },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label), maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            content(Modifier.padding(padding).fillMaxSize())
        }
    }
}

@Composable
private fun SubscriptionSwitcher(names: List<Pair<Long, String>>, activeId: Long, onSelect: (Long) -> Unit) {
    if (names.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .novaFocus(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                names.firstOrNull { it.first == activeId }?.second.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.width(96.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (id == activeId) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; onSelect(id) },
                )
            }
        }
    }
}
