package com.nova.iptv.data.account

import android.content.Context
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.cloud.child
import com.nova.iptv.data.cloud.num
import com.nova.iptv.data.cloud.objects
import com.nova.iptv.data.cloud.str
import com.nova.iptv.data.cloud.bool
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.repo.SubscriptionInput
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.data.tmdb.TmdbRepository
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

/**
 * Subscriptions and the TMDB key follow the Google account: users/<uid>/subs/<id> = {n,t,s,u,p,m,up,x}
 * and users/<uid>/settings/tmdb. Same format as the Apple and Samsung apps; newest wins, deletions travel.
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val cloud: Cloud,
    private val subs: SubscriptionRepository,
    private val tmdb: TmdbRepository,
) {
    private val prefs = context.getSharedPreferences("nova_account", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _email = MutableStateFlow(cloud.accountEmail.takeIf { cloud.accountUid != null })
    val email: StateFlow<String?> = _email

    val available: Boolean get() = cloud.googleAvailable
    val signedIn: Boolean get() = cloud.accountUid != null

    /** Local subscription id ↔ id in the account. */
    private fun cloudId(localId: Long): String =
        prefs.getString("c|$localId", null) ?: ("a" + UUID.randomUUID().toString().replace("-", "").take(16)).also {
            prefs.edit().putString("c|$localId", it).putString("l|$it", localId.toString()).apply()
        }
    private fun localId(cid: String): Long? = prefs.getString("l|$cid", null)?.toLongOrNull()
    private fun link(localId: Long, cid: String) = prefs.edit().putString("c|$localId", cid).putString("l|$cid", localId.toString()).apply()
    private fun updated(localId: Long): Double = prefs.getString("ut|$localId", null)?.toDoubleOrNull() ?: 0.0
    private fun setUpdated(localId: Long, t: Double) = prefs.edit().putString("ut|$localId", t.toString()).apply()

    fun onSignedIn(email: String) { _email.value = email }

    fun signOut() { cloud.signOut(); _email.value = null }

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun encode(sub: SubscriptionEntity, up: Double): Map<String, Any> {
        val input = subs.toInput(sub)
        return mapOf(
            "n" to sub.name,
            "t" to if (sub.type == SourceType.XTREAM) "xtream" else "m3u",
            "s" to input.server, "u" to input.username, "p" to input.password,
            "m" to input.m3uUrl, "up" to up,
        )
    }

    /** Call after a subscription was added or edited. */
    fun pushSub(localId: Long) = scope.launch {
        val uid = cloud.accountUid ?: return@launch
        val sub = subs.get(localId) ?: return@launch
        if (sub.type == SourceType.M3U_FILE) return@launch   // a file on this device can't travel
        val t = now(); setUpdated(localId, t)
        cloud.put("users/$uid/subs/${cloudId(localId)}", encode(sub, t))
    }

    /** Call when a subscription is removed, so it disappears on the other devices too. */
    fun deleteSub(localId: Long) = scope.launch {
        val uid = cloud.accountUid ?: return@launch
        val cid = prefs.getString("c|$localId", null) ?: return@launch
        cloud.put("users/$uid/subs/$cid", mapOf("x" to true, "up" to now()))
        prefs.edit().remove("c|$localId").remove("l|$cid").remove("ut|$localId").apply()
    }

    fun pushTmdb(key: String) = scope.launch {
        val uid = cloud.accountUid ?: return@launch
        cloud.put("users/$uid/settings/tmdb", key)
    }

    /** Merges the account's subscriptions with this device's. Returns true when local subscriptions changed. */
    suspend fun pull(): Boolean {
        val uid = cloud.accountUid ?: return false
        val remote = cloud.get("users/$uid") ?: com.google.gson.JsonObject()
        val rs = remote.child("subs")?.objects().orEmpty()
        var changed = false
        val locals = subs.getAll()
        for ((cid, d) in rs) {
            val up = d.num("up")
            val lid = localId(cid) ?: locals.firstOrNull { l ->   // same credentials already here: link instead of duplicating
                prefs.getString("c|${l.id}", null) == null && sameSub(l, d)
            }?.also { link(it.id, cid) }?.id
            val local = lid?.let { id -> locals.firstOrNull { it.id == id } }
            if (d.bool("x")) {
                if (local != null && updated(local.id) <= up) { subs.delete(local); changed = true }
                continue
            }
            val xtream = d.str("t") != "m3u"
            val input = SubscriptionInput(
                id = local?.id ?: 0L, name = d.str("n"),
                type = if (xtream) SourceType.XTREAM else SourceType.M3U_URL,
                server = d.str("s"), username = d.str("u"), password = d.str("p"), m3uUrl = d.str("m"),
                epgUrl = local?.let { subs.toInput(it).epgUrl } ?: "",
                userAgent = local?.userAgent ?: "", liveFormat = local?.liveFormat ?: "ts",
            )
            when {
                local == null -> { val id = subs.save(input); link(id, cid); setUpdated(id, up); changed = true }
                updated(local.id) < up -> { subs.save(input); setUpdated(local.id, up); changed = true }
                updated(local.id) > up -> pushSub(local.id)
            }
        }
        for (l in subs.getAll()) {
            val cid = prefs.getString("c|${l.id}", null)
            if (cid == null || rs[cid] == null) pushSub(l.id)
        }
        val key = remote.child("settings")?.str("tmdb").orEmpty()
        val mine = tmdb.currentKey()
        if (key.isNotEmpty() && mine.isEmpty()) runCatching { tmdb.saveKey(key) }
        else if (key.isEmpty() && mine.isNotEmpty()) pushTmdb(mine)
        return changed
    }

    private fun sameSub(l: SubscriptionEntity, d: com.google.gson.JsonObject): Boolean {
        val i = subs.toInput(l)
        return if (d.str("t") == "m3u") l.type == SourceType.M3U_URL && i.m3uUrl.trim() == d.str("m").trim()
        else l.type == SourceType.XTREAM && i.username == d.str("u") &&
            i.server.trimEnd('/').lowercase() == d.str("s").trimEnd('/').lowercase()
    }
}
