package com.nova.iptv.data.network

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.toNovaException
import com.nova.iptv.di.DEFAULT_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.Reader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpFetcher @Inject constructor(private val api: RemoteApi) {

    private suspend fun open(url: String, userAgent: String, authorization: String? = null): okhttp3.ResponseBody {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            throw NovaException(ErrorKind.INVALID_URL)
        }
        val resp = try {
            if (authorization != null) api.getAuth(url, userAgent.ifBlank { DEFAULT_USER_AGENT }, authorization)
            else api.get(url, userAgent.ifBlank { DEFAULT_USER_AGENT })
        } catch (e: Exception) {
            throw e.toNovaException()
        }
        if (!resp.isSuccessful) {
            resp.errorBody()?.close()
            val code = resp.code()
            throw if (code == 401 || code == 403) NovaException(ErrorKind.AUTH_FAILED, code)
            else NovaException(ErrorKind.HTTP, code)
        }
        return resp.body() ?: throw NovaException(ErrorKind.PARSE)
    }

    /** Fetches a small text document (optionally with an Authorization header). */
    suspend fun string(url: String, userAgent: String, authorization: String? = null): String = withContext(Dispatchers.IO) {
        open(url, userAgent, authorization).use { it.string() }
    }

    /** Fetches a URL and hands a streaming character reader to [block]. */
    suspend fun <T> text(url: String, userAgent: String, block: (Reader) -> T): T = withContext(Dispatchers.IO) {
        val body = open(url, userAgent)
        body.use { b ->
            try {
                block(b.charStream())
            } catch (e: NovaException) {
                throw e
            } catch (e: MalformedJsonException) {
                throw NovaException(ErrorKind.PARSE, cause = e)
            } catch (e: JsonParseException) {
                throw NovaException(ErrorKind.PARSE, cause = e)
            } catch (e: IllegalStateException) {
                throw NovaException(ErrorKind.PARSE, cause = e)
            } catch (e: Exception) {
                throw e.toNovaException()
            }
        }
    }

    /** Fetches a URL and hands the raw byte stream to [block]. */
    suspend fun <T> bytes(url: String, userAgent: String, block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val body = open(url, userAgent)
        body.use { b ->
            try {
                block(b.byteStream())
            } catch (e: NovaException) {
                throw e
            } catch (e: IllegalStateException) {
                throw NovaException(ErrorKind.NOT_PLAYLIST, cause = e)
            } catch (e: org.xmlpull.v1.XmlPullParserException) {
                throw NovaException(ErrorKind.PARSE, cause = e)
            } catch (e: Exception) {
                throw e.toNovaException()
            }
        }
    }
}
