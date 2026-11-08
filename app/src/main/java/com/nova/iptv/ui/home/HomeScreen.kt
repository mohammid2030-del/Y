@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.nova.iptv.ui.theme.NovaGradient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIVE(R.string.tab_live, Icons.Filled.LiveTv),
    MOVIES(R.string.tab_movies, Icons.Filled.Movie),
    SERIES(R.string.tab_series, Icons.Filled.VideoLibrary),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
    FAVORITES(R.string.tab_favorites, Icons.Filled.Favorite),
}

/** Tabs shown inside the floating pill; Favorites lives in the account menu (and as a row on Home). */
private val PillTabs = listOf(HomeTab.HOME, HomeTab.LIVE, HomeTab.MOVIES, HomeTab.SERIES, HomeTab.SEARCH)

@Composable
fun HomeScreen(
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddSubscription: () -> Unit,
    onSwitchProfile: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = HomeTab.entries[tabIndex.coerceIn(0, HomeTab.entries.lastIndex)]
    val flags = LocalNovaFlags.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720 || flags.isTv
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val goTo: (HomeTab) -> Unit = { tabIndex = it.ordinal }

    BackHandler(enabled = tabIndex != 0) { tabIndex = 0 }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (activeId == 0L) {
            Column(
                Modifier.fillMaxSize().padding(top = homeTopInset()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyView(stringResource(R.string.no_subscription), Modifier.weight(1f))
                Button(onClick = onAddSubscription, modifier = Modifier.padding(24.dp).novaFocus()) {
                    Text(stringResource(R.string.add_subscription))
                }
            }
        } else {
            val padded = Modifier.fillMaxSize().padding(top = homeTopInset())
            when (tab) {
                HomeTab.HOME -> DiscoverHome(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries, onGoTo = goTo)
                HomeTab.LIVE -> Box(padded) { LiveTab(wide = wide) }
                HomeTab.MOVIES -> VodTab(vm = hiltViewModel<MoviesViewModel>(), onOpen = onOpenMovie)
                HomeTab.SERIES -> VodTab(vm = hiltViewModel<SeriesViewModel>(), onOpen = onOpenSeries)
                HomeTab.FAVORITES -> Box(padded) { FavoritesTab(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries) }
                HomeTab.SEARCH -> Box(padded) { SearchTab(onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries) }
            }
        }

        // Soft scrim so the floating navigation stays readable over scrolled content.
        Box(
            Modifier
                .fillMaxWidth()
                .height(homeTopInset() + 12.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.92f), MaterialTheme.colorScheme.background.copy(alpha = 0f))
                    )
                )
        )
        // Floating glass navigation.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (flags.isTv) 18.dp else 10.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (wide) {
                NovaLogo(40)
                Spacer(Modifier.width(8.dp))
            }
            NavPill(selected = tab, wide = wide, onSelect = goTo)
            AccountButton(
                subs = subs.map { it.id to it.name },
                activeId = activeId,
                favoritesSelected = tab == HomeTab.FAVORITES,
                onSelectSub = vm::switchTo,
                onFavorites = { goTo(HomeTab.FAVORITES) },
                onEpg = onOpenEpg,
                onSettings = onOpenSettings,
                onAdd = onAddSubscription,
                onSwitchProfile = onSwitchProfile,
            )
        }
    }
}

@Composable
private fun NavPill(selected: HomeTab, wide: Boolean, onSelect: (HomeTab) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .shadow(18.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PillTabs.forEach { t ->
            val isSel = t == selected
            val itemShape = RoundedCornerShape(50)
            val tint by animateColorAsState(
                if (isSel) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), label = "tint",
            )
            Row(
                Modifier
                    .novaFocus(itemShape)
                    .clip(itemShape)
                    .then(if (isSel) Modifier.background(NovaGradient) else Modifier)
                    .clickable { onSelect(t) }
                    .padding(horizontal = if (isSel || wide) 14.dp else 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, stringResource(t.label), tint = tint, modifier = Modifier.size(20.dp))
                AnimatedVisibility(
                    visible = isSel || wide,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Text(
                        stringResource(t.label),
                        color = tint,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountButton(
    subs: List<Pair<Long, String>>,
    activeId: Long,
    favoritesSelected: Boolean,
    onSelectSub: (Long) -> Unit,
    onFavorites: () -> Unit,
    onEpg: () -> Unit,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .novaFocus(CircleShape)
                .shadow(18.dp, CircleShape)
                .size(46.dp)
                .clip(CircleShape)
                .background(if (favoritesSelected) NovaGradient else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)))
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (favoritesSelected) Icons.Filled.Favorite else Icons.Filled.Person,
                stringResource(R.string.menu_account),
                tint = if (favoritesSelected) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (subs.isNotEmpty()) {
                Text(
                    stringResource(R.string.subscriptions_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                subs.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1) },
                        leadingIcon = {
                            if (id == activeId) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = { open = false; if (id != activeId) onSelectSub(id) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_subscription)) },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { open = false; onAdd() },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.switch_profile)) },
                leadingIcon = { Icon(Icons.Filled.Person, null) },
                onClick = { open = false; onSwitchProfile() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_favorites)) },
                leadingIcon = { Icon(Icons.Filled.Favorite, null) },
                onClick = { open = false; onFavorites() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tv_guide)) },
                leadingIcon = { Icon(Icons.Filled.CalendarViewDay, null) },
                onClick = { open = false; onEpg() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { open = false; onSettings() },
            )
        }
    }
}
