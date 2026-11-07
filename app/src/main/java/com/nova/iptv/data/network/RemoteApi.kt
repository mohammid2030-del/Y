package com.nova.iptv.data.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Every provider has its own host, so all calls take a full dynamic URL.
 * Responses are streamed and parsed incrementally to keep memory flat for huge lists.
 */
interface RemoteApi {
    @Streaming
    @GET
    suspend fun get(
        @Url url: String,
        @Header("User-Agent") userAgent: String,
    ): Response<ResponseBody>
}
