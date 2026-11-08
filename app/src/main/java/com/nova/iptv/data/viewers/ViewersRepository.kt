package com.nova.iptv.data.viewers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.cloud.bool
import com.nova.iptv.data.cloud.child
import com.nova.iptv.data.cloud.num
import com.nova.iptv.data.cloud.objects
import com.nova.iptv.data.cloud.str
import com.nova.iptv.data.db.FavoriteDao
import com.nova.iptv.data.db.FavoriteEntity
import com.nova.iptv.data.db.HistoryDao
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionDao
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A person watching on a subscription (up to 5): own progress, favourites and recent channels, optional 4-digit PIN. */
data class Viewer(
    val id: String,
    val name: String,
    val color: Int = 0,
    val pinHash: String? = null,
    val updated: Double = 0.0,
    val deleted: Boolean = false,
) {
    val hasPin: Boolean get() = !pinHash.isNullOrEmpty()
}

/**
 * Viewers per subscription and who is watching now. History / favourites keep using the subscription id column as
 * an "owner": the first viewer ("main") is the subscription itself (existing data stays), the others get a
 * private negative id. Progress syncs through Firebase under the subscription's own key, shared with the
 * Apple and Samsung apps.
 */
@Singleton
class ViewersRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val cloud: Cloud,
    private val subDao: SubscriptionDao,
    private val historyDao: HistoryDao,
    private val favoriteDao: FavoriteDao,
    private val crypto: CryptoManager,
) {
    private val prefs = context.getSharedPreferences("nova_viewers", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listType = object : TypeToken<List<Viewer>>() {}.type

    /** (subscription id, viewer id) — changes re-run every history / favourites query. */
    private val _current = MutableStateFlow(0L to "main")
    val current: StateFlow<Pair<Long, String>> = _current

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun all(subId: Long): List<Viewer> =
        prefs.getString("viewers|$subId", null)?.let { runCatching { gson.fromJson<List<Viewer>>(it, listType) }.getOrNull() }
            ?: listOf(Viewer("main", defaultName(), 0))

    fun visible(subId: Long): List<Viewer> = all(subId).filter { !it.deleted }.ifEmpty { listOf(Viewer("main", defaultName())) }

    private fun saveAll(subId: Long, list: List<Viewer>) = prefs.edit().putString("viewers|$subId", gson.toJson(list)).apply()

    private fun defaultName() = if (java.util.Locale.getDefault().language == "ar") "أنا" else "Me"

    fun savedViewer(subId: Long): String = prefs.getString("viewer|$subId", "main") ?: "main"

    /** Owner id used in the history / favourites tables. */
    fun ownerFor(subId: Long, viewerId: String = if (_current.value.first == subId) _current.value.second else savedViewer(subId)): Long {
        if (viewerId == "main" || subId <= 0) return subId
        val key = "slot|$subId|$viewerId"
        var slot = prefs.getInt(key, 0)
        if (slot == 0) {
            val used = prefs.all.keys.filter { it.startsWith("slot|$subId|") }.mapNotNull { prefs.getInt(it, 0).takeIf { s -> s > 0 } }.toSet()
            slot = (1..15).firstOrNull { it !in used } ?: 15
            prefs.edit().putInt(key, slot).apply()
        }
        return -(subId * 16 + slot)
    }

    /** Subscription id for an owner id (inverse of ownerFor). */
    fun subOf(owner: Long): Long = if (owner >= 0) owner else (-owner) / 16

    fun select(subId: Long, viewerId: String) {
        prefs.edit().putString("viewer|$subId", viewerId).apply()
        _current.value = subId to viewerId
        scope.launch { pullData(subId, viewerId) }
    }

    /** Called when a subscription becomes active without going through the picker. */
    fun restore(subId: Long) {
        val v = savedViewer(subId).takeIf { id -> visible(subId).any { it.id == id } } ?: visible(subId).first().id
        if (_current.value != subId to v) _current.value = subId to v
    }

    fun current(subId: Long): Viewer? = visible(subId).firstOrNull { it.id == (if (_current.value.first == subId) _current.value.second else savedViewer(subId)) }

    suspend fun pinHash(subId: Long, pin: String): String = Cloud.sha256hex((subKey(subId) ?: subId.toString()) + ":" + pin)

    suspend fun checkPin(subId: Long, v: Viewer, pin: String): Boolean = !v.hasPin || v.pinHash == pinHash(subId, pin)

    suspend fun save(subId: Long, id: String?, name: String, color: Int, pin: String?, clearPin: Boolean) {
        val list = all(subId).toMutableList()
        val now = System.currentTimeMillis() / 1000.0
        if (id != null) {
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return
            var v = list[i].copy(name = name, color = color, updated = now)
            if (clearPin) v = v.copy(pinHash = null) else if (pin != null && pin.length == 4) v = v.copy(pinHash = pinHash(subId, pin))
            list[i] = v; push(subId, v)
        } else {
            if (list.count { !it.deleted } >= 5) return
            var v = Viewer(UUID.randomUUID().toString().replace("-", "").take(12), name, color, updated = now)
            if (pin != null && pin.length == 4) v = v.copy(pinHash = pinHash(subId, pin))
            list += v; push(subId, v)
        }
        saveAll(subId, list)
    }

    suspend fun delete(subId: Long, v: Viewer) {
        val list = all(subId).toMutableList()
        if (list.count { !it.deleted } <= 1) return
        val i = list.indexOfFirst { it.id == v.id }.takeIf { it >= 0 } ?: return
        list[i] = list[i].copy(deleted = true, updated = System.currentTimeMillis() / 1000.0)
        push(subId, list[i])
        val owner = ownerFor(subId, v.id)
        historyDao.clear(owner); favoriteDao.deleteBySub(owner)
        saveAll(subId, list)
        if (_current.value == subId to v.id) restore(subId)
    }

    // ---- cloud

    /** Private folder shared by every device signed in to this subscription. */
    suspend fun subKey(subId: Long): String? {
        val sub = subDao.get(subId) ?: return null
        val secret = runCatching { crypto.decrypt(sub.secretCipher) }.getOrDefault("")
        val raw = when (sub.type) {
            SourceType.XTREAM -> "${XtreamUrls.normalizeServer(sub.serverUrl).lowercase()}|${sub.username}|$secret"
            SourceType.M3U_URL -> secret
            else -> ""
        }
        if (raw.length <= 3) return null
        return Cloud.sha256hex("nova:$raw").take(40)
    }

    private suspend fun path(subId: Long, rest: String): String? = if (!cloud.enabled) null else subKey(subId)?.let { "nova/v1/$it/$rest" }

    private fun push(subId: Long, v: Viewer) = scope.launch {
        val p = path(subId, "profiles/${Cloud.safe(v.id)}") ?: return@launch
        cloud.put(p, buildMap {
            put("n", v.name); put("c", v.color); put("u", v.updated)
            if (!v.pinHash.isNullOrEmpty()) put("p", v.pinHash)
            if (v.deleted) put("x", true)
        })
    }

    private fun historyKey(h: HistoryEntity) = Cloud.safe("${h.type}_${h.remoteId}_${h.episodeId}")

    private fun encode(h: HistoryEntity): Map<String, Any> = mapOf(
        "k" to h.type, "i" to h.remoteId, "n" to h.title, "l" to h.logo, "c" to "", "x" to h.containerExt, "y" to "",
        "e" to h.episodeId, "ex" to h.containerExt, "s" to h.subtitle, "p" to h.positionMs, "d" to h.durationMs, "u" to h.updatedAt / 1000.0,
    )

    /** Push one progress entry (called after each save). */
    fun pushHistory(h: HistoryEntity) = scope.launch {
        val sub = subOf(h.subscriptionId)
        val viewer = viewerOfOwner(sub, h.subscriptionId) ?: return@launch
        val p = path(sub, "data/${Cloud.safe(viewer)}/h/${historyKey(h)}") ?: return@launch
        cloud.put(p, encode(h))
    }

    fun pushFavorite(f: FavoriteEntity, deleted: Boolean) = scope.launch {
        val sub = subOf(f.subscriptionId)
        val viewer = viewerOfOwner(sub, f.subscriptionId) ?: return@launch
        val p = path(sub, "data/${Cloud.safe(viewer)}/f/${Cloud.safe("${f.type}_${f.remoteId}")}") ?: return@launch
        cloud.put(p, buildMap {
            put("k", f.type); put("i", f.remoteId); put("n", f.name); put("l", f.logo); put("c", ""); put("x", ""); put("y", "")
            put("u", System.currentTimeMillis() / 1000.0)
            if (deleted) put("del", true)
        })
    }

    fun clearRemoteHistory(owner: Long) = scope.launch {
        val sub = subOf(owner)
        val viewer = viewerOfOwner(sub, owner) ?: return@launch
        path(sub, "data/${Cloud.safe(viewer)}/h")?.let { cloud.put(it, emptyMap<String, Any>()) }
    }

    private fun viewerOfOwner(subId: Long, owner: Long): String? =
        if (owner == subId) "main" else all(subId).firstOrNull { ownerFor(subId, it.id) == owner }?.id

    /** Viewers: newest change wins on each side; deletions travel as tombstones. Returns true when the list changed. */
    suspend fun pullViewers(subId: Long): Boolean {
        val p = path(subId, "profiles") ?: return false
        val remote = cloud.get(p)?.objects() ?: emptyMap()
        val list = all(subId).toMutableList()
        var changed = false
        for ((id, d) in remote) {
            val rv = Viewer(id, d.str("n"), d.num("c").toInt(), d.str("p").ifEmpty { null }, d.num("u"), d.bool("x"))
            val i = list.indexOfFirst { it.id == id }
            when {
                i < 0 -> { list += rv; changed = true }
                rv.updated > list[i].updated -> { list[i] = rv; changed = true }
                rv.updated < list[i].updated -> push(subId, list[i])
            }
        }
        list.filter { it.id !in remote }.forEach { push(subId, it) }
        if (changed) saveAll(subId, list)
        _status.value = "ok"
        return changed
    }

    /** Progress and favourites of a viewer: newest wins per title, both ways. */
    suspend fun pullData(subId: Long, viewerId: String) {
        val p = path(subId, "data/${Cloud.safe(viewerId)}") ?: return
        val owner = ownerFor(subId, viewerId)
        val remote = cloud.get(p) ?: com.google.gson.JsonObject()
        val local = historyDao.allFor(owner).associateBy { historyKey(it) }
        val seen = HashSet<String>()
        for ((_, d) in remote.child("h")?.objects() ?: emptyMap()) {
            val type = d.str("k"); val rid = d.str("i")
            if (type.isEmpty() || rid.isEmpty()) continue
            val h = HistoryEntity(
                historyKey = HistoryEntity.key(owner, type, rid, d.str("e")), subscriptionId = owner, type = type, remoteId = rid,
                episodeId = d.str("e"), title = d.str("n"), subtitle = d.str("s"), logo = d.str("l"),
                containerExt = d.str("ex").ifEmpty { d.str("x") }, positionMs = d.num("p").toLong(), durationMs = d.num("d").toLong(),
                updatedAt = (d.num("u") * 1000).toLong(),
            )
            val k = historyKey(h); seen += k
            val mine = local[k]
            when {
                mine == null || h.updatedAt > mine.updatedAt -> historyDao.upsert(h)
                h.updatedAt < mine.updatedAt -> pushHistory(mine)
            }
        }
        local.filterKeys { it !in seen }.values.forEach { pushHistory(it) }
        val favs = favoriteDao.allFor(owner)
        val rf = remote.child("f")?.objects() ?: emptyMap()
        for ((_, d) in rf) {
            val type = d.str("k"); val rid = d.str("i")
            if (type.isEmpty() || rid.isEmpty()) continue
            val has = favs.any { it.type == type && it.remoteId == rid }
            if (d.bool("del")) { if (has) favoriteDao.delete(owner, type, rid) }
            else if (!has) favoriteDao.upsert(FavoriteEntity(owner, type, rid, d.str("n"), d.str("l")))
        }
        favs.filter { f -> rf.keys.none { it == Cloud.safe("${f.type}_${f.remoteId}") } }.forEach { pushFavorite(it, false) }
        _status.value = "ok"
    }
}
