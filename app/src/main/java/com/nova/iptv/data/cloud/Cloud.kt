package com.nova.iptv.data.cloud

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.BuildConfig
import com.nova.iptv.data.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Realtime Database over REST (same wire format as the Apple and Samsung apps):
 *  - nova/v1/<sha256 of the subscription's server|user|pass>/profiles|data : viewers and their progress
 *  - users/<google uid>/subs|settings : subscriptions that follow the Google account
 * Auth: Google (TV device code) when signed in, otherwise anonymous.
 */
@Singleton
class Cloud @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: CryptoManager,
) {
    private val prefs = context.getSharedPreferences("nova_cloud", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val lock = Mutex()
    private var idToken = ""
    private var expiry = 0L

    val enabled: Boolean get() = BuildConfig.FIREBASE_DB_URL.isNotEmpty() && BuildConfig.FIREBASE_API_KEY.isNotEmpty()
    val googleAvailable: Boolean get() = enabled && BuildConfig.GOOGLE_TV_CLIENT_ID.isNotEmpty() && BuildConfig.GOOGLE_TV_CLIENT_SECRET.isNotEmpty()
    val accountUid: String? get() = if (secret("user_refresh").isEmpty()) null else prefs.getString("uid", null)
    val accountEmail: String? get() = prefs.getString("email", null)

    private fun secret(k: String) = prefs.getString(k, null)?.let { runCatching { crypto.decrypt(it) }.getOrDefault("") }.orEmpty()
    private fun setSecret(k: String, v: String?) = prefs.edit().apply { if (v.isNullOrEmpty()) remove(k) else putString(k, crypto.encrypt(v)) }.apply()

    private fun post(url: String, form: Map<String, String>? = null, json: String? = null): JsonObject? = runCatching {
        val body = form?.let { f -> FormBody.Builder().apply { f.forEach { (a, b) -> add(a, b) } }.build() }
            ?: (json ?: "{}").toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            JsonParser.parseString(r.body?.string().orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
        }
    }.getOrNull()

    private suspend fun token(): String? = lock.withLock {
        if (idToken.isNotEmpty() && System.currentTimeMillis() < expiry) return idToken
        val key = BuildConfig.FIREBASE_API_KEY
        secret("user_refresh").takeIf { it.isNotEmpty() }?.let { refresh ->
            val j = post("https://securetoken.googleapis.com/v1/token?key=$key", form = mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
            j?.str("id_token")?.takeIf { it.isNotEmpty() }?.let { t ->
                idToken = t; expiry = System.currentTimeMillis() + (j.str("expires_in").toLongOrNull() ?: 3600) * 1000 - 120_000
                j.str("refresh_token").takeIf { it.isNotEmpty() }?.let { setSecret("user_refresh", it) }
                return t
            }
        }
        secret("anon_refresh").takeIf { it.isNotEmpty() }?.let { refresh ->
            val j = post("https://securetoken.googleapis.com/v1/token?key=$key", form = mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
            j?.str("id_token")?.takeIf { it.isNotEmpty() }?.let { t ->
                idToken = t; expiry = System.currentTimeMillis() + (j.str("expires_in").toLongOrNull() ?: 3600) * 1000 - 120_000
                return t
            }
        }
        val s = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key", json = """{"returnSecureToken":true}""") ?: return null
        val t = s.str("idToken").takeIf { it.isNotEmpty() } ?: return null
        idToken = t; expiry = System.currentTimeMillis() + (s.str("expiresIn").toLongOrNull() ?: 3600) * 1000 - 120_000
        setSecret("anon_refresh", s.str("refreshToken"))
        return t
    }

    private suspend fun call(method: String, path: String, value: Any? = null): JsonElement? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val t = token() ?: return@withContext null
        val base = BuildConfig.FIREBASE_DB_URL.trimEnd('/')
        val url = "$base/$path.json?auth=" + URLEncoder.encode(t, "UTF-8")
        runCatching {
            val body = value?.let { gson.toJson(it).toRequestBody("application/json".toMediaType()) }
            val req = Request.Builder().url(url).method(method, if (method == "GET" || method == "DELETE") null else (body ?: "null".toRequestBody())).build()
            http.newCall(req).execute().use { r ->
                if (r.code == 401) { idToken = ""; return@runCatching null }
                if (!r.isSuccessful) return@runCatching null
                JsonParser.parseString(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun get(path: String): JsonObject? = call("GET", path)?.takeIf { it.isJsonObject }?.asJsonObject
    suspend fun put(path: String, value: Any) { call("PUT", path, value) }
    /** A single value (string / number) as text, "" when absent. */
    suspend fun raw(path: String): String = call("GET", path)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    // ---- Google account (code on screen, google.com/device on the phone)

    /** Last Google / Firebase error, shown on the sign-in screen so a setup problem is visible. */
    @Volatile var lastError: String = ""
        private set

    private fun describe(j: JsonObject?): String {
        if (j == null) return "network"
        j.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { return it.str("message") }
        val code = j.str("error"); val desc = j.str("error_description")
        return if (desc.isEmpty()) code else "$code: $desc"
    }

    data class DeviceCode(val device: String, val user: String, val url: String, val interval: Int, val expires: Long)
    sealed interface Login { data class Ok(val email: String) : Login; object Pending : Login; object Denied : Login; object Expired : Login; object Failed : Login }

    suspend fun startGoogle(): DeviceCode? = withContext(Dispatchers.IO) {
        if (!googleAvailable) { lastError = "not_configured"; return@withContext null }
        var j: JsonObject? = null
        for (attempt in 0 until 3) {   // a brand-new client or a flaky network: try a few times
            j = post("https://oauth2.googleapis.com/device/code", form = mapOf("client_id" to BuildConfig.GOOGLE_TV_CLIENT_ID, "scope" to "openid email profile"))
            if (!j?.str("device_code").isNullOrEmpty()) break
            if (attempt < 2) kotlinx.coroutines.delay(1500)
        }
        val res = j
        val dc = res?.str("device_code")?.takeIf { it.isNotEmpty() } ?: run { lastError = describe(res); return@withContext null }
        lastError = ""
        val j2 = res
        DeviceCode(dc, j2.str("user_code"), j2.str("verification_url").ifEmpty { "https://www.google.com/device" },
            maxOf(5, j2.str("interval").toIntOrNull() ?: 5), System.currentTimeMillis() + maxOf(60, j2.str("expires_in").toIntOrNull() ?: 900) * 1000L)
    }

    suspend fun pollGoogle(code: DeviceCode): Login = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() > code.expires) return@withContext Login.Expired
        val j = post("https://oauth2.googleapis.com/token", form = mapOf(
            "client_id" to BuildConfig.GOOGLE_TV_CLIENT_ID, "client_secret" to BuildConfig.GOOGLE_TV_CLIENT_SECRET,
            "device_code" to code.device, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")) ?: return@withContext Login.Pending
        when (j.str("error")) {
            "authorization_pending", "slow_down" -> return@withContext Login.Pending
            "access_denied" -> return@withContext Login.Denied
            "expired_token" -> return@withContext Login.Expired
            "" -> Unit
            else -> { lastError = describe(j); return@withContext Login.Failed }
        }
        val gid = j.str("id_token").takeIf { it.isNotEmpty() } ?: run { lastError = "no id_token"; return@withContext Login.Failed }
        val payload = gson.toJson(mapOf("postBody" to "id_token=$gid&providerId=google.com", "requestUri" to "http://localhost", "returnSecureToken" to true))
        val f = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=${BuildConfig.FIREBASE_API_KEY}", json = payload)
        val t = f?.str("idToken")?.takeIf { it.isNotEmpty() } ?: run { lastError = "Firebase: " + describe(f); return@withContext Login.Failed }
        lock.withLock {
            idToken = t; expiry = System.currentTimeMillis() + (f.str("expiresIn").toLongOrNull() ?: 3600) * 1000 - 120_000
        }
        setSecret("user_refresh", f!!.str("refreshToken"))
        prefs.edit().putString("uid", f.str("localId")).putString("email", f.str("email")).apply()
        Login.Ok(f.str("email"))
    }

    fun signOut() {
        setSecret("user_refresh", null)
        prefs.edit().remove("uid").remove("email").apply()
        idToken = ""
    }

    companion object {
        fun sha256hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Firebase keys can't contain . $ # [ ] / */
        fun safe(s: String): String = s.map { if (it in ".$#[]/") '_' else it }.joinToString("")
    }
}

fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
fun JsonObject.num(name: String): Double = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
fun JsonObject.objects(): Map<String, JsonObject> = entrySet().mapNotNull { (k, v) -> if (v.isJsonObject) k to v.asJsonObject else null }.toMap()
fun JsonObject.child(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
