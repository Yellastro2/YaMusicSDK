package com.yellastrodev.yamusicsdk.network

import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

internal enum class YamHttpMethod {
    GET,
    POST
}

internal sealed interface YamHttpBody {
    data class Form(val fields: Map<String, String>) : YamHttpBody
    data class Json(val value: String) : YamHttpBody
}

internal data class YamHttpRequest(
    val method: YamHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: YamHttpBody? = null,
    val requiresAuthorization: Boolean = true
)

internal data class YamHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface YamTransport {
    suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse>
}

internal fun interface YamContentTransport {
    suspend fun retrieve(
        url: String,
        requiresAuthorization: Boolean
    ): YamResult<ByteArray>
}

/**
 * Внутренний OkHttp transport. Логирует только метод, путь, статус и время без тела и токена.
 */
internal class YamHttpTransport(
    private val accessToken: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    connectionFactory: YamConnectionFactory = YamConnectionFactory(),
    private val logger: YamLogger
) : YamTransport, YamContentTransport, AutoCloseable {

    @Volatile
    private var clients =
        connectionFactory.createClients(
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        )

    /** Новые запросы будут выполняться через обновлённую конфигурацию прокси. */
    fun updateProxyConfig(
        proxyConfig: YamProxyConfig?,
    ) {
        val updatedClients =
            YamConnectionFactory(
                proxyConfig,
                logger,
            ).createClients(
                connectTimeoutMillis = connectTimeoutMillis,
                readTimeoutMillis = readTimeoutMillis,
            )
        val previousClients = clients
        clients = updatedClients
        previousClients.close()
    }

    /** Освобождает connection pools и регистрацию SOCKS5-аутентификации. */
    override fun close() {
        clients.close()
    }

    override suspend fun execute(
        request: YamHttpRequest
    ): YamResult<YamHttpResponse> = withContext(Dispatchers.IO) {
        val token = accessToken()
        if (request.requiresAuthorization && token.isBlank()) {
            logger.warning(
                TAG,
                "[execute] ${request.method} ${request.path}: отсутствует авторизация",
            )
            return@withContext YamResult.Failure(YamError.Unauthorized)
        }

        val startedAt = System.nanoTime()
        logger.debug(TAG, "[execute] Начат запрос ${request.method} ${request.path}")

        try {
            retryOnceOnConnectTimeout(
                shouldRetry = request.method == YamHttpMethod.GET,
                onRetry = {
                    logger.warning(
                        TAG,
                        "[execute] ${request.method} ${request.path}: " +
                            "таймаут подключения, повтор 1/1",
                    )
                },
            ) {
                clients.regular
                    .newCall(
                        buildRequest(
                            request = request,
                            token = token,
                        ),
                    )
                    .execute()
                    .use { response ->
                        val statusCode = response.code
                        val responseBody = response.body?.string().orEmpty()
                        val elapsedMs = (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
                        logger.debug(
                            TAG,
                            "[execute] ${request.method} ${request.path}: HTTP $statusCode, ${elapsedMs}мс",
                        )

                        if (response.isSuccessful) {
                            YamResult.Success(
                                YamHttpResponse(
                                    statusCode = statusCode,
                                    body = responseBody,
                                ),
                            )
                        } else {
                            YamResult.Failure(mapHttpError(statusCode, responseBody))
                        }
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            logger.error(TAG, "[execute] ${request.method} ${request.path}: таймаут", error)
            YamResult.Failure(YamError.Timeout)
        } catch (error: UnknownHostException) {
            logger.error(TAG, "[execute] ${request.method} ${request.path}: нет сети", error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: ConnectException) {
            logger.error(TAG, "[execute] ${request.method} ${request.path}: нет соединения", error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: IOException) {
            logger.error(TAG, "[execute] ${request.method} ${request.path}: ошибка ввода-вывода", error)
            YamResult.Failure(YamError.Network(error))
        } catch (error: Exception) {
            logger.error(TAG, "[execute] ${request.method} ${request.path}: сетевая ошибка", error)
            YamResult.Failure(YamError.Network(error))
        }
    }

    override suspend fun retrieve(
        url: String,
        requiresAuthorization: Boolean,
    ): YamResult<ByteArray> = withContext(Dispatchers.IO) {
        val logPath = "/external-content"
        val token = accessToken()

        if (requiresAuthorization && token.isBlank()) {
            logger.warning(
                TAG,
                "[retrieve] $logPath: отсутствует авторизация",
            )

            return@withContext YamResult.Failure(
                YamError.Unauthorized,
            )
        }

        val startedAt = System.nanoTime()
        logger.debug(
            TAG,
            "[retrieve] Начат запрос GET $logPath",
        )

        try {
            retryOnceOnConnectTimeout(
                onRetry = {
                    logger.warning(
                        TAG,
                        "[retrieve] GET $logPath: таймаут подключения, повтор 1/1",
                    )
                },
            ) {
                retrieveContentOnce(
                    url = url,
                    token = token,
                    requiresAuthorization = requiresAuthorization,
                    logPath = logPath,
                    startedAt = startedAt,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            logger.error(
                TAG,
                "[retrieve] GET $logPath: таймаут",
                error,
            )

            YamResult.Failure(
                YamError.Timeout,
            )
        } catch (error: UnknownHostException) {
            logger.error(
                TAG,
                "[retrieve] GET $logPath: нет сети",
                error,
            )

            YamResult.Failure(
                YamError.NoInternet,
            )
        } catch (error: ConnectException) {
            logger.error(
                TAG,
                "[retrieve] GET $logPath: нет соединения",
                error,
            )

            YamResult.Failure(
                YamError.NoInternet,
            )
        } catch (error: IOException) {
            logger.error(
                TAG,
                "[retrieve] GET $logPath: ошибка ввода-вывода",
                error,
            )

            YamResult.Failure(
                YamError.Network(error),
            )
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[retrieve] GET $logPath: сетевая ошибка",
                error,
            )

            YamResult.Failure(
                YamError.Network(error),
            )
        }
    }

    private fun retrieveContentOnce(
        url: String,
        token: String,
        requiresAuthorization: Boolean,
        logPath: String,
        startedAt: Long,
    ): YamResult<ByteArray> {
        var currentUrl = URI(url)
        var redirectCount = 0

        while (true) {
            val response =
                clients.manualRedirects
                    .newCall(
                        buildContentRequest(
                            url = currentUrl.toString(),
                            token = token,
                            requiresAuthorization = requiresAuthorization,
                        ),
                    )
                    .execute()

            try {
                val statusCode = response.code

                if (statusCode in REDIRECT_STATUS_CODES) {
                    val location = response.header("Location")

                    if (location.isNullOrBlank()) {
                        return YamResult.Failure(
                            YamError.InvalidResponse(
                                IllegalStateException(
                                    "HTTP $statusCode без Location",
                                ),
                            ),
                        )
                    }

                    redirectCount++

                    if (redirectCount > MAX_REDIRECTS) {
                        return YamResult.Failure(
                            YamError.InvalidResponse(
                                IllegalStateException(
                                    "Слишком много HTTP redirects",
                                ),
                            ),
                        )
                    }

                    logger.debug(
                        TAG,
                        "[retrieve] GET $logPath: HTTP $statusCode, redirect=$redirectCount",
                    )

                    currentUrl = currentUrl.resolve(location)
                    continue
                }

                val responseBody = response.body

                if (response.isSuccessful) {
                    val elapsedMs =
                        (System.nanoTime() - startedAt) /
                            NANOS_IN_MILLISECOND
                    logger.debug(
                        TAG,
                        "[retrieve] GET $logPath: HTTP $statusCode, ${elapsedMs}мс",
                    )
                    return YamResult.Success(
                        responseBody?.bytes() ?: ByteArray(0),
                    )
                }

                val elapsedMs =
                    (System.nanoTime() - startedAt) /
                        NANOS_IN_MILLISECOND
                logger.debug(
                    TAG,
                    "[retrieve] GET $logPath: HTTP $statusCode, ${elapsedMs}мс",
                )

                return YamResult.Failure(
                    mapHttpError(
                        statusCode,
                        responseBody?.string().orEmpty(),
                    ),
                )
            } finally {
                response.close()
            }
        }
    }

    private fun buildRequest(
        request: YamHttpRequest,
        token: String,
    ): Request {
        val builder =
            Request.Builder()
                .url(buildUrl(request))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Yandex-Music-Client", YANDEX_MUSIC_CLIENT)

        if (request.requiresAuthorization) {
            builder.header("Authorization", "OAuth $token")
        }

        return when (request.method) {
            YamHttpMethod.GET -> builder.get()
            YamHttpMethod.POST -> builder.post(request.body.toRequestBody())
        }.build()
    }

    private fun buildContentRequest(
        url: String,
        token: String,
        requiresAuthorization: Boolean,
    ): Request {
        val builder =
            Request.Builder()
                .url(url)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)

        if (requiresAuthorization) {
            builder.header("Authorization", "OAuth $token")
        }

        return builder.build()
    }

    private fun buildUrl(request: YamHttpRequest): String {
        val normalizedPath = if (request.path.startsWith("/")) {
            request.path
        } else {
            "/${request.path}"
        }
        val query = request.query
            .takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
            ?.let { "?$it" }
            .orEmpty()
        return "$baseUrl$normalizedPath$query"
    }

    private fun YamHttpBody?.toRequestBody(): RequestBody =
        when (this) {
            is YamHttpBody.Form ->
                fields.entries
                    .joinToString("&") { (key, value) ->
                        "${key.urlEncoded()}=${value.urlEncoded()}"
                    }
                    .toRequestBody(FORM_MEDIA_TYPE)
            is YamHttpBody.Json -> value.toRequestBody(JSON_MEDIA_TYPE)
            null -> ByteArray(0).toRequestBody(null)
        }

    private fun mapHttpError(statusCode: Int, body: String): YamError {
        if (statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN) {
            return YamError.Unauthorized
        }

        val details = parseErrorDetails(body)
        return YamError.Http(
            statusCode = statusCode,
            code = details.first,
            description = details.second
        )
    }

    private fun parseErrorDetails(body: String): Pair<String?, String?> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val errorElement = root["error"]
            val code = runCatching {
                errorElement?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: runCatching {
                errorElement?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val description = root["errorDescription"]?.jsonPrimitive?.contentOrNull
                ?: root["error_description"]?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: runCatching {
                    errorElement?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            code to description
        } catch (_: Exception) {
            null to null
        }
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val TAG = "YamNetwork"
        const val DEFAULT_BASE_URL = "https://api.music.yandex.net"
        const val USER_AGENT = "Yandex-Music-API"
        const val YANDEX_MUSIC_CLIENT = "YandexMusicAndroid/24023621"
        const val NANOS_IN_MILLISECOND = 1_000_000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val MAX_REDIRECTS = 8
        val FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded".toMediaType()
        val JSON_MEDIA_TYPE =
            "application/json".toMediaType()
        val REDIRECT_STATUS_CODES =
            setOf(301, 302, 303, 307, 308)
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Повторяет разрешённую вызывающим кодом операцию ровно один раз после connect timeout. */
internal inline fun <T> retryOnceOnConnectTimeout(
    shouldRetry: Boolean = true,
    onRetry: (SocketTimeoutException) -> Unit,
    block: () -> T,
): T {
    var retryAvailable = true

    while (true) {
        try {
            return block()
        } catch (error: IOException) {
            val connectTimeout = error.connectTimeoutCauseOrNull()
            if (!shouldRetry || !retryAvailable || connectTimeout == null) {
                throw error
            }
            retryAvailable = false
            onRetry(connectTimeout)
        }
    }
}

internal fun IOException.connectTimeoutCauseOrNull(): SocketTimeoutException? {
    var current: Throwable? = this
    while (current != null) {
        if (
            current is SocketTimeoutException &&
            current.isConnectTimeout()
        ) {
            return current
        }
        current = current.cause
    }
    return null
}

private fun SocketTimeoutException.isConnectTimeout(): Boolean =
    message?.contains(
        other = "connect",
        ignoreCase = true,
    ) == true ||
        stackTrace.any { frame ->
            frame.methodName == "timedFinishConnect"
        }
