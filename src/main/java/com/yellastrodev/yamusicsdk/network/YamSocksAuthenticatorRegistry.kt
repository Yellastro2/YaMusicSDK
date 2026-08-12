package com.yellastrodev.yamusicsdk.network

import java.lang.reflect.Method
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Маршрутизирует JVM SOCKS5-аутентификацию только на зарегистрированный адрес прокси.
 *
 * Стандартный SOCKS socket JVM не принимает authenticator на уровне отдельного клиента,
 * поэтому реестр устанавливает один глобальный делегат. На JVM 9+ предыдущий обработчик
 * сохраняется и восстанавливается; Android не предоставляет API для его чтения.
 */
internal object YamSocksAuthenticatorRegistry {

    private val lock = Any()
    private val entries = mutableMapOf<ProxyKey, CredentialEntry>()
    private var nextOwnerId = 0L
    private var installedRouter: RoutingAuthenticator? = null

    fun register(
        proxyConfig: YamProxyConfig?,
    ): AutoCloseable {
        if (
            proxyConfig?.type != YamProxyType.SOCKS ||
            proxyConfig.username.isNullOrEmpty()
        ) {
            return EmptyRegistration
        }

        val key = ProxyKey(
            host = proxyConfig.host.normalizedHost(),
            port = proxyConfig.port,
        )
        val username = proxyConfig.username
        val password = proxyConfig.password.orEmpty()

        val ownerId = synchronized(lock) {
            ensureRouterInstalled()

            val entry = entries[key]
            if (entry == null) {
                entries[key] = CredentialEntry(
                    username = username,
                    password = password.toCharArray(),
                )
            } else {
                require(entry.matches(username, password)) {
                    "Для одного SOCKS5-прокси нельзя одновременно использовать разные учётные данные"
                }
            }

            val id = ++nextOwnerId
            entries.getValue(key).owners += id
            id
        }

        return Registration(
            key = key,
            ownerId = ownerId,
        )
    }

    private fun ensureRouterInstalled() {
        if (defaultAuthenticatorReader == null) {
            val router = installedRouter ?: RoutingAuthenticator(previous = null)
                .also { installedRouter = it }
            Authenticator.setDefault(router)
            return
        }

        val current = readDefaultAuthenticator()
        if (installedRouter != null && current === installedRouter) {
            return
        }

        RoutingAuthenticator(
            previous = current,
        ).also { router ->
            Authenticator.setDefault(router)
            installedRouter = router
        }
    }

    private fun unregister(
        key: ProxyKey,
        ownerId: Long,
    ) {
        synchronized(lock) {
            val entry = entries[key] ?: return
            entry.owners -= ownerId
            if (entry.owners.isEmpty()) {
                entry.password.fill('\u0000')
                entries -= key
            }

            if (entries.isEmpty()) {
                val router = installedRouter
                if (
                    router != null &&
                    defaultAuthenticatorReader != null &&
                    readDefaultAuthenticator() === router
                ) {
                    Authenticator.setDefault(router.previous)
                    installedRouter = null
                }
            }
        }
    }

    private fun readDefaultAuthenticator(): Authenticator? =
        runCatching {
            defaultAuthenticatorReader?.invoke(null) as? Authenticator
        }.getOrNull()

    private fun credentialsFor(
        host: String?,
        port: Int,
        protocol: String?,
    ): PasswordAuthentication? {
        if (!protocol.equals(SOCKS5_PROTOCOL, ignoreCase = true)) {
            return null
        }
        val normalizedHost = host?.normalizedHost() ?: return null
        return synchronized(lock) {
            entries[ProxyKey(normalizedHost, port)]
                ?.toPasswordAuthentication()
        }
    }

    private class RoutingAuthenticator(
        val previous: Authenticator?,
    ) : Authenticator() {

        override fun getPasswordAuthentication(): PasswordAuthentication? =
            credentialsFor(
                host = requestingHost,
                port = requestingPort,
                protocol = requestingProtocol,
            ) ?: previous?.let { authenticator ->
                runCatching {
                    previousAuthenticatorRequester?.invoke(
                        authenticator,
                        requestingHost,
                        requestingSite,
                        requestingPort,
                        requestingProtocol,
                        requestingPrompt,
                        requestingScheme,
                        requestingURL,
                        requestorType,
                    ) as? PasswordAuthentication
                }.getOrNull()
            }
    }

    private data class ProxyKey(
        val host: String,
        val port: Int,
    )

    private class CredentialEntry(
        private val username: String,
        val password: CharArray,
    ) {
        val owners = mutableSetOf<Long>()

        fun matches(
            username: String,
            password: String,
        ): Boolean =
            this.username == username &&
                this.password.size == password.length &&
                this.password.indices.all { index ->
                    this.password[index] == password[index]
                }

        fun toPasswordAuthentication(): PasswordAuthentication =
            PasswordAuthentication(
                username,
                password.copyOf(),
            )
    }

    private class Registration(
        private val key: ProxyKey,
        private val ownerId: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                unregister(key, ownerId)
            }
        }
    }

    private object EmptyRegistration : AutoCloseable {
        override fun close() = Unit
    }

    private fun String.normalizedHost(): String =
        trim()
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase(Locale.ROOT)

    private const val SOCKS5_PROTOCOL = "SOCKS5"

    private val defaultAuthenticatorReader: Method? =
        Authenticator::class.java.methods.firstOrNull { method ->
            method.name == "getDefault" && method.parameterCount == 0
        }

    private val previousAuthenticatorRequester: Method? =
        Authenticator::class.java.methods.firstOrNull { method ->
            method.name == "requestPasswordAuthenticationInstance" &&
                method.parameterCount == 8
        }
}
