package com.yellastrodev.yamusicsdk.network

import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.yamusicsdk.YamLogger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Пара клиентов одного transport; закрытие освобождает сокеты вне вызывающего потока. */
internal class YamConnectionClients(
    val regular: OkHttpClient,
    val manualRedirects: OkHttpClient,
    private val socksAuthRegistration: AutoCloseable,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        cleanupExecutor.execute {
            try {
                listOf(regular, manualRedirects).forEach { client ->
                    runCatching { client.connectionPool.evictAll() }
                    runCatching { client.dispatcher.executorService.shutdown() }
                }
            } finally {
                socksAuthRegistration.close()
            }
        }
    }

    private companion object {
        val cleanupExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "yam-network-cleanup").apply {
                isDaemon = true
            }
        }
    }
}

/** Создаёт изолированные HTTP-клиенты SDK с единым прокси и proxy-auth. */
internal class YamConnectionFactory(
    private val proxyConfig: YamProxyConfig? = null,
    private val logger: YamLogger = NoOpYamLogger,
) {
    internal val proxy: Proxy? =
        proxyConfig?.toJavaProxy()

    private val proxyCredential: String? =
        proxyConfig
            ?.takeIf {
                it.type == YamProxyType.HTTP &&
                    !it.username.isNullOrEmpty()
            }
            ?.let {
                Credentials.basic(
                    it.username.orEmpty(),
                    it.password.orEmpty(),
                )
            }

    init {
        if (proxyConfig == null) {
            logger.debug(
                TAG,
                "[init] Прокси не настроен",
            )
        } else {
            logger.debug(
                TAG,
                "[init] ${proxyConfig.type}-прокси ${proxyConfig.host}:${proxyConfig.port}; " +
                    "auth=${!proxyConfig.username.isNullOrEmpty()}; " +
                    "usernameLength=${proxyConfig.username?.length ?: 0}; " +
                    "passwordLength=${proxyConfig.password?.length ?: 0}",
            )
        }
    }

    fun createClients(
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): YamConnectionClients {
        val registration = YamSocksAuthenticatorRegistry.register(proxyConfig)
        return try {
            YamConnectionClients(
                regular = createClient(
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    followRedirects = true,
                ),
                manualRedirects = createClient(
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    followRedirects = false,
                ),
                socksAuthRegistration = registration,
            )
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
    }

    private fun createClient(
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        followRedirects: Boolean,
    ): OkHttpClient {
        val builder =
            OkHttpClient.Builder()
                .connectTimeout(
                    connectTimeoutMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
                .readTimeout(
                    readTimeoutMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)

        proxy?.let(builder::proxy)

        proxyCredential?.let { credential ->
            builder.proxyAuthenticator { _, response ->
                val credentialAlreadySent =
                    response.request.header(PROXY_AUTHORIZATION) != null

                val preemptive =
                    response.header(PROXY_AUTHENTICATE) ==
                        OKHTTP_PREEMPTIVE_CHALLENGE

                logger.debug(
                    TAG,
                    "[proxyAuthenticator] Запрошен proxy-auth; " +
                        "preemptive=$preemptive; " +
                        "credentialAlreadySent=$credentialAlreadySent",
                )

                if (credentialAlreadySent) {
                    null
                } else {
                    response.request
                        .newBuilder()
                        .header(
                            PROXY_AUTHORIZATION,
                            credential,
                        )
                        .build()
                }
            }
        }

        return builder.build()
    }

    private fun YamProxyConfig.toJavaProxy(): Proxy =
        Proxy(
            when (type) {
                YamProxyType.HTTP -> Proxy.Type.HTTP
                YamProxyType.SOCKS -> Proxy.Type.SOCKS
            },
            InetSocketAddress.createUnresolved(
                host,
                port,
            ),
        )

    private companion object {
        const val TAG =
            "YamProxy"

        const val PROXY_AUTHORIZATION =
            "Proxy-Authorization"

        const val PROXY_AUTHENTICATE =
            "Proxy-Authenticate"

        const val OKHTTP_PREEMPTIVE_CHALLENGE =
            "OkHttp-Preemptive"
    }
}
