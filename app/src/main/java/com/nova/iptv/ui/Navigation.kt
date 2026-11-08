package com.nova.iptv.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nova.iptv.ui.epg.EpgScreen
import com.nova.iptv.ui.home.HomeScreen
import com.nova.iptv.ui.onboarding.AddSubscriptionScreen
import com.nova.iptv.ui.onboarding.LanguageScreen
import com.nova.iptv.ui.onboarding.ProfilesScreen
import com.nova.iptv.ui.onboarding.SignInScreen
import com.nova.iptv.ui.onboarding.ViewersScreen
import com.nova.iptv.ui.onboarding.SyncScreen
import com.nova.iptv.ui.onboarding.WelcomeScreen
import com.nova.iptv.ui.settings.HiddenCategoriesScreen
import com.nova.iptv.ui.settings.SettingsScreen
import com.nova.iptv.ui.vod.MovieDetailScreen
import com.nova.iptv.ui.vod.SeriesDetailScreen

object Routes {
    const val WELCOME = "welcome"
    const val LANGUAGE = "language"
    const val ADD = "add?id={id}"
    const val SYNC = "sync/{id}"
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val MATCH_PREFS = "match_prefs"
    const val MOVIE = "movie/{id}"
    const val SERIES = "series/{id}"
    const val EPG = "epg"
    const val SETTINGS = "settings"
    const val HIDDEN = "hidden"
    const val SIGNIN = "signin"
    const val VIEWERS = "viewers?manage={manage}"
    fun viewers(manage: Boolean = false) = "viewers?manage=$manage"

    fun add(id: Long = 0L) = "add?id=$id"
    fun sync(id: Long) = "sync/$id"
    fun movie(id: Long) = "movie/$id"
    fun series(id: Long) = "series/$id"
}

fun NavHostController.goHomeClearing() {
    navigate(Routes.HOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun NovaNavHost(startDestination: String, googleFirst: Boolean = false) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onStart = { nav.navigate(Routes.LANGUAGE) })
        }
        composable(Routes.LANGUAGE) {
            LanguageScreen(onContinue = {
                nav.navigate(if (googleFirst) Routes.SIGNIN else Routes.add()) { popUpTo(Routes.WELCOME) { inclusive = true } }
            })
        }
        composable(Routes.SIGNIN) {
            val fromSettings = nav.previousBackStackEntry != null
            SignInScreen(
                skipIsLater = fromSettings,
                onDone = { hasSubs ->
                    when {
                        fromSettings -> nav.popBackStack()
                        hasSubs -> nav.navigate(Routes.PROFILES) { popUpTo(nav.graph.id) { inclusive = true } }
                        else -> nav.navigate(Routes.add()) { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                },
                onSkip = {
                    if (fromSettings) nav.popBackStack()
                    else nav.navigate(Routes.add()) { popUpTo(nav.graph.id) { inclusive = true } }
                },
            )
        }
        composable(Routes.VIEWERS, arguments = listOf(navArgument("manage") { type = NavType.BoolType; defaultValue = false })) { e ->
            val manage = e.arguments?.getBoolean("manage") == true
            ViewersScreen(
                manage = manage,
                onChosen = { nav.goHomeClearing() },
                onBack = if (manage) ({ nav.popBackStack(); Unit }) else ({
                    nav.navigate(Routes.PROFILES) { popUpTo(nav.graph.id) { inclusive = true } }; Unit
                }),
            )
        }
        composable(
            Routes.ADD,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }),
        ) {
            AddSubscriptionScreen(
                onSaved = { id -> nav.navigate(Routes.sync(id)) },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack(); Unit }) else null,
            )
        }
        composable(Routes.SYNC, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            SyncScreen(
                onDone = { nav.navigate(Routes.viewers()) { popUpTo(nav.graph.id) { inclusive = true } } },
                onEdit = { id -> nav.navigate(Routes.add(id)) },
            )
        }
        composable(Routes.MATCH_PREFS) {
            com.nova.iptv.ui.settings.MatchPrefsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onChosen = { nav.navigate(Routes.viewers()) },
                onAdd = { nav.navigate(Routes.add()) },
                onManage = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMovie = { nav.navigate(Routes.movie(it)) },
                onOpenSeries = { nav.navigate(Routes.series(it)) },
                onOpenEpg = { nav.navigate(Routes.EPG) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onAddSubscription = { nav.navigate(Routes.add()) },
                onMatchPrefs = { nav.navigate(Routes.MATCH_PREFS) },
                onSwitchProfile = {
                    nav.navigate(Routes.PROFILES) {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.MOVIE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            MovieDetailScreen(onBack = { nav.popBackStack() }, onOpenMovie = { nav.navigate(Routes.movie(it)) })
        }
        composable(Routes.SERIES, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            SeriesDetailScreen(onBack = { nav.popBackStack() }, onOpenSeries = { nav.navigate(Routes.series(it)) })
        }
        composable(Routes.EPG) {
            EpgScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onAdd = { nav.navigate(Routes.add()) },
                onEdit = { nav.navigate(Routes.add(it)) },
                onSync = { nav.navigate(Routes.sync(it)) },
                onHiddenCategories = { nav.navigate(Routes.HIDDEN) },
                onMatchPrefs = { nav.navigate(Routes.MATCH_PREFS) },
                onSignIn = { nav.navigate(Routes.SIGNIN) },
                onViewers = { nav.navigate(Routes.viewers(manage = true)) },
                onSwitchViewer = { nav.navigate(Routes.viewers()) { popUpTo(nav.graph.id) { inclusive = true } } },
                onAllRemoved = { nav.navigate(Routes.add()) { popUpTo(nav.graph.id) { inclusive = true } } },
            )
        }
        composable(Routes.HIDDEN) {
            HiddenCategoriesScreen(onBack = { nav.popBackStack() })
        }
    }
}
