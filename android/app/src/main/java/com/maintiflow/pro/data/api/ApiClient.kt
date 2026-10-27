package com.maintiflow.pro.data.api

import com.maintiflow.pro.BuildConfig
import com.maintiflow.pro.data.SessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val session: SessionStore) {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    /** Émis quand le serveur refuse le jeton (session expirée ou révoquée). */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val token = session.token
            val authed = if (token != null && req.header("Authorization") == null)
                req.newBuilder().header("Authorization", "Bearer $token").build() else req
            val resp = chain.proceed(authed)
            if (resp.code == 401 && token != null && !req.url.encodedPath.endsWith("auth/login")) {
                _sessionExpired.tryEmit(Unit)
            }
            resp
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    @Volatile private var cached: Pair<String, MaintiFlowApi>? = null

    val api: MaintiFlowApi
        get() {
            val base = session.serverUrl.ifEmpty { "https://serveur.invalid/api/v1/" }
            cached?.let { if (it.first == base) return it.second }
            val created = Retrofit.Builder()
                .baseUrl(base)
                .client(http)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(MaintiFlowApi::class.java)
            cached = base to created
            return created
        }

    /** Message lisible pour l'utilisateur à partir d'une erreur réseau / HTTP. */
    fun messageOf(t: Throwable): String = when (t) {
        is HttpException -> {
            val body = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
            val detail = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["detail"] }.getOrNull() }
            when (detail) {
                is JsonPrimitive -> detail.content
                is JsonArray -> "Données invalides : " + detail.joinToString { e ->
                    runCatching { e.jsonObject["msg"]!!.jsonPrimitive.content }.getOrDefault("?")
                }
                else -> "Erreur serveur (${t.code()})"
            }
        }
        is IOException -> "Serveur injoignable. Vérifiez le réseau."
        else -> t.message ?: "Erreur inattendue"
    }
}

fun Throwable.isNetwork(): Boolean = this is IOException
fun Throwable.httpCode(): Int? = (this as? HttpException)?.code()
