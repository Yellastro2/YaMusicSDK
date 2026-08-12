package com.yellastrodev.yamusicsdk.auth

import com.yellastrodev.yamusicsdk.network.YamConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class OAuthHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface DeviceAuthTransport {
    suspend fun postForm(url: String, fields: Map<String, String>): OAuthHttpResponse
}

/** Выполняет OAuth Device Flow через тот же OkHttp proxy transport, что и API. */
internal class OkHttpDeviceAuthTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    connectionFactory: YamConnectionFactory = YamConnectionFactory(),
) : DeviceAuthTransport {

    private val client =
        connectionFactory.createClient(
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            followRedirects = true,
        )

    override suspend fun postForm(
        url: String,
        fields: Map<String, String>
    ): OAuthHttpResponse = withContext(Dispatchers.IO) {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }

        val request =
            Request.Builder()
                .url(url)
                .post(
                    body.toRequestBody(FORM_MEDIA_TYPE),
                )
                .header(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Yandex-Music-Client", YANDEX_MUSIC_CLIENT)
                .build()

        client
            .newCall(request)
            .execute()
            .use { response ->
                OAuthHttpResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val USER_AGENT = "Yandex-Music-API"
        const val YANDEX_MUSIC_CLIENT = "YandexMusicAndroid/24023621"
        val FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    }
}
