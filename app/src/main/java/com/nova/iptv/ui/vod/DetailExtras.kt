package com.nova.iptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.library.LibraryRepository
import com.nova.iptv.data.library.Version
import com.nova.iptv.data.tmdb.KnownWork
import com.nova.iptv.data.tmdb.PersonInfo
import com.nova.iptv.data.tmdb.TmdbPerson
import com.nova.iptv.data.tmdb.TmdbRepository
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.novaFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Versions (copies of the same title) and the cast person sheet — shared by the movie and series pages. */
class DetailExtras(private val library: LibraryRepository, private val tmdb: TmdbRepository, private val scope: CoroutineScope) {
    var versions by mutableStateOf<List<Version>>(emptyList())
        private set
    /** Asked on every entry when there's more than one copy (Arabic first), like the Apple and Samsung apps. */
    var choosing by mutableStateOf(false)
    var person by mutableStateOf<PersonInfo?>(null)
        private set
    var personLoading by mutableStateOf<TmdbPerson?>(null)
        private set
    val found = mutableStateMapOf<String, Long?>()
    private var asked = false

    fun load(c: ContentEntity) = scope.launch {
        versions = runCatching { library.versionsOf(c.subscriptionId, c.type, c.id) }.getOrDefault(emptyList())
        if (!asked && versions.size > 1) { asked = true; choosing = true }
    }

    fun openPerson(p: TmdbPerson, subId: Long) {
        if (p.id <= 0) return
        personLoading = p
        scope.launch {
            val info = tmdb.person(p.id)
            if (personLoading != p) return@launch   // cancelled
            personLoading = null
            person = info
            info?.known?.forEach { w -> launch { found["${w.isTv}|${w.id}"] = runCatching { library.find(subId, w) }.getOrNull() } }
        }
    }

    fun closePerson() { person = null; personLoading = null; found.clear() }
}

@Composable
fun VersionDialog(versions: List<Version>, currentId: Long, onPick: (Version) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_version)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(versions.size) { i ->
                    val v = versions[i]
                    val q = LibraryRepository.qualityLabel(v.quality)
                    Row(
                        Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .novaFocus(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                            .background(if (v.item.id == currentId) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(v) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(listOf(v.language, q).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { v.category }, fontWeight = FontWeight.Bold)
                            Text(v.item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (v.category.isNotEmpty()) Text(v.category, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Actor page: photo, bio and the titles they're known for — the ones in this subscription open directly. */
@Composable
fun PersonDialog(p: PersonInfo, found: Map<String, Long?>, onOpen: (KnownWork, Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(p.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    NetImage(p.photo, Modifier.size(96.dp, 136.dp).clip(RoundedCornerShape(12.dp)), fallback = Icons.Filled.Person)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        if (p.birthday.isNotBlank()) Text(stringResource(R.string.born, p.birthday), style = MaterialTheme.typography.bodySmall)
                        if (p.place.isNotBlank()) Text(p.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.bio.isNotBlank()) Text(p.bio, style = MaterialTheme.typography.bodySmall, maxLines = 7, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (p.known.isNotEmpty()) {
                    Text(stringResource(R.string.known_for), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    // the ones you can watch first
                    val sorted = p.known.sortedBy { if (found["${it.isTv}|${it.id}"] != null) 0 else 1 }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(sorted, key = { "${it.isTv}|${it.id}" }) { w ->
                            val id = found["${w.isTv}|${w.id}"]
                            Column(
                                Modifier.width(96.dp).novaFocus(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = id != null) { id?.let { onOpen(w, it) } }
                                    .alpha(if (id != null) 1f else 0.45f),
                            ) {
                                NetImage(w.poster, Modifier.size(96.dp, 140.dp).clip(RoundedCornerShape(10.dp)))
                                Text(w.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (id != null) w.year else stringResource(R.string.not_in_library), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun PersonLoading(name: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }, title = { Text(name) }, text = {
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)) }
    })
}

fun KnownWork.contentType() = if (isTv) ContentType.SERIES else ContentType.MOVIE
