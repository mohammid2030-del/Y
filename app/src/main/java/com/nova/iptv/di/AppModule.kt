package com.nova.iptv.di

import android.content.Context
import androidx.room.Room
import com.nova.iptv.data.db.ContentDao
import com.nova.iptv.data.db.EpgDao
import com.nova.iptv.data.db.FavoriteDao
import com.nova.iptv.data.db.HistoryDao
import com.nova.iptv.data.db.NovaDatabase
import com.nova.iptv.data.db.SubscriptionDao
import com.nova.iptv.data.network.RemoteApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

const val DEFAULT_USER_AGENT = "NOVA-IPTV/1.0 (Linux; Android)"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared HTTP client. No logging interceptor is installed on purpose:
     * IPTV URLs embed credentials and must never reach logcat.
     * TLS uses the platform trust store and hostname verification (no "trust all").
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build())
            } else {
                chain.proceed(req)
            }
        }
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .build()

    @Provides
    @Singleton
    fun remoteApi(retrofit: Retrofit): RemoteApi = retrofit.create(RemoteApi::class.java)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NovaDatabase =
        Room.databaseBuilder(context, NovaDatabase::class.java, "nova.db")
            .addMigrations(com.nova.iptv.data.db.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun subscriptionDao(db: NovaDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun contentDao(db: NovaDatabase): ContentDao = db.contentDao()
    @Provides fun epgDao(db: NovaDatabase): EpgDao = db.epgDao()
    @Provides fun favoriteDao(db: NovaDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun historyDao(db: NovaDatabase): HistoryDao = db.historyDao()
}
