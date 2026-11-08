package com.nova.iptv.data.sports

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class MatchPrefsState(
    val competitions: Set<String> = DEFAULT_COMPETITIONS,
    val teams: List<FavTeam> = emptyList(),
    val onlyFavorites: Boolean = false,
)

/** The user's followed competitions and favourite teams for the matches strip. */
@Singleton
class MatchPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("nova_match_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<MatchPrefsState> = _state

    private fun load(): MatchPrefsState {
        val comps = prefs.getStringSet("competitions", null)?.toSet() ?: DEFAULT_COMPETITIONS
        val teams: List<FavTeam> = runCatching {
            gson.fromJson<List<FavTeam>>(prefs.getString("teams", "[]"), object : TypeToken<List<FavTeam>>() {}.type)
        }.getOrNull().orEmpty()
        return MatchPrefsState(comps, teams, prefs.getBoolean("only_fav", false))
    }

    private fun save(s: MatchPrefsState) {
        prefs.edit()
            .putStringSet("competitions", s.competitions)
            .putString("teams", gson.toJson(s.teams))
            .putBoolean("only_fav", s.onlyFavorites)
            .apply()
        _state.value = s
    }

    fun toggleCompetition(slug: String) {
        val s = _state.value
        save(s.copy(competitions = if (slug in s.competitions) s.competitions - slug else s.competitions + slug))
    }

    fun toggleTeam(t: FavTeam) {
        val s = _state.value
        save(s.copy(teams = if (s.teams.any { it.id == t.id }) s.teams.filterNot { it.id == t.id } else s.teams + t))
    }

    fun setOnlyFavorites(v: Boolean) = save(_state.value.copy(onlyFavorites = v))
}
