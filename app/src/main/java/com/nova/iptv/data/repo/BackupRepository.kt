package com.nova.iptv.data.repo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.data.db.FavoriteDao
import com.nova.iptv.data.db.FavoriteEntity
import com.nova.iptv.data.security.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted, passphrase-protected export / import of user settings and subscriptions. */
@Singleton
class BackupRepository @Inject constructor(
    private val subs: SubscriptionRepository,
    private val favoriteDao: FavoriteDao,
    private val settings: SettingsRepository,
) {
    suspend fun exportData(passphrase: String): ByteArray = withContext(Dispatchers.Default) {
        val root = JsonObject()
        root.addProperty("version", 1)
        val subsArr = JsonArray()
        subs.getAll().forEach { s ->
            val i = subs.toInput(s)
            subsArr.add(JsonObject().apply {
                addProperty("oldId", s.id)
                addProperty("name", i.name)
                addProperty("type", i.type)
                addProperty("server", i.server)
                addProperty("username", i.username)
                addProperty("password", i.password)
                addProperty("m3uUrl", i.m3uUrl)
                addProperty("fileUri", i.fileUri)
                addProperty("epgUrl", i.epgUrl)
                addProperty("userAgent", i.userAgent)
                addProperty("liveFormat", i.liveFormat)
            })
        }
        root.add("subscriptions", subsArr)
        val favArr = JsonArray()
        favoriteDao.all().forEach { f ->
            favArr.add(JsonObject().apply {
                addProperty("sub", f.subscriptionId)
                addProperty("type", f.type)
                addProperty("remoteId", f.remoteId)
                addProperty("name", f.name)
                addProperty("logo", f.logo)
                addProperty("position", f.position)
            })
        }
        root.add("favorites", favArr)
        val set = JsonObject()
        settings.exportMap().forEach { (k, v) -> set.addProperty(k, v) }
        root.add("settings", set)
        BackupCrypto.encrypt(root.toString(), passphrase)
    }

    /** @return ids of the newly created subscriptions. Throws IllegalArgumentException on bad file/passphrase. */
    suspend fun restoreData(data: ByteArray, passphrase: String): List<Long> = withContext(Dispatchers.Default) {
        val json = BackupCrypto.decrypt(data, passphrase)
        val root = JsonParser.parseString(json).asJsonObject
        val idMap = HashMap<Long, Long>()
        val created = ArrayList<Long>()
        val subsArr = root.getAsJsonArray("subscriptions") ?: com.google.gson.JsonArray()
        for (el in subsArr) {
            val o = el.asJsonObject
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val newId = subs.save(
                SubscriptionInput(
                    name = s("name"), type = s("type"), server = s("server"), username = s("username"),
                    password = s("password"), m3uUrl = s("m3uUrl"), fileUri = s("fileUri"),
                    epgUrl = s("epgUrl"), userAgent = s("userAgent"), liveFormat = s("liveFormat").ifBlank { "ts" },
                )
            )
            idMap[o.get("oldId")?.asLong ?: -1L] = newId
            created += newId
        }
        val favs = root.getAsJsonArray("favorites")?.mapNotNull { el ->
            val o = el.asJsonObject
            val sub = idMap[o.get("sub")?.asLong ?: -1L] ?: return@mapNotNull null
            FavoriteEntity(
                subscriptionId = sub,
                type = o.get("type").asString,
                remoteId = o.get("remoteId").asString,
                name = o.get("name")?.asString.orEmpty(),
                logo = o.get("logo")?.asString.orEmpty(),
                position = o.get("position")?.asInt ?: 0,
            )
        }.orEmpty()
        if (favs.isNotEmpty()) favoriteDao.upsertAll(favs)
        root.getAsJsonObject("settings")?.let { so ->
            settings.importMap(so.entrySet().associate { (k, v) -> k to v.asString })
        }
        created
    }
}
