package com.yellastrodev.yamusicsdk.network

import java.nio.charset.StandardCharsets

/** Тип прокси, используемого только сетевым слоем Яндекс Музыки. */
enum class YamProxyType {
    HTTP,
    /** SOCKS5 через стандартный JVM socket transport. */
    SOCKS,
}

/**
 * Необязательная конфигурация прокси для всех HTTP-запросов SDK.
 *
 * Логин и пароль поддерживаются для HTTP- и SOCKS5-прокси. SOCKS5 использует
 * внутренний маршрутизатор JVM Authenticator, ограниченный адресом прокси.
 */
data class YamProxyConfig(
    val host: String,
    val port: Int,
    val type: YamProxyType = YamProxyType.HTTP,
    val username: String? = null,
    val password: String? = null,
) {
    init {
        require(host.isNotBlank()) {
            "Адрес прокси не может быть пустым"
        }
        require(port in MIN_PORT..MAX_PORT) {
            "Порт прокси должен быть в диапазоне $MIN_PORT..$MAX_PORT"
        }
        if (type == YamProxyType.SOCKS) {
            val hasUsername = !username.isNullOrEmpty()
            val hasPassword = !password.isNullOrEmpty()
            require(hasUsername == hasPassword) {
                "Для SOCKS5 необходимо указать одновременно логин и пароль"
            }
            if (hasUsername) {
                require(username.length in MIN_CREDENTIAL_LENGTH..MAX_CREDENTIAL_LENGTH) {
                    "Длина логина SOCKS5 должна быть в диапазоне " +
                        "$MIN_CREDENTIAL_LENGTH..$MAX_CREDENTIAL_LENGTH"
                }
                require(password!!.length in MIN_CREDENTIAL_LENGTH..MAX_CREDENTIAL_LENGTH) {
                    "Длина пароля SOCKS5 должна быть в диапазоне " +
                        "$MIN_CREDENTIAL_LENGTH..$MAX_CREDENTIAL_LENGTH"
                }
                require(SOCKS_CHARSET.newEncoder().canEncode(username)) {
                    "Логин SOCKS5 должен быть совместим с ISO-8859-1"
                }
                require(SOCKS_CHARSET.newEncoder().canEncode(password)) {
                    "Пароль SOCKS5 должен быть совместим с ISO-8859-1"
                }
            }
        }
    }

    internal companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
        const val MIN_CREDENTIAL_LENGTH = 1
        const val MAX_CREDENTIAL_LENGTH = 255
        val SOCKS_CHARSET = StandardCharsets.ISO_8859_1
    }
}
