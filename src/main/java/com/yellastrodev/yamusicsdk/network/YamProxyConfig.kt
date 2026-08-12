package com.yellastrodev.yamusicsdk.network

/** Тип прокси, используемого только сетевым слоем Яндекс Музыки. */
enum class YamProxyType {
    HTTP,
    SOCKS,
}

/**
 * Необязательная конфигурация прокси для всех HTTP-запросов SDK.
 *
 * Логин и пароль поддерживаются для HTTP-прокси. Для SOCKS-прокси учётные
 * данные не принимаются, чтобы не устанавливать глобальный JVM Authenticator.
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
        require(
            type == YamProxyType.HTTP ||
                (username.isNullOrEmpty() && password.isNullOrEmpty())
        ) {
            "Аутентификация SOCKS-прокси не поддерживается"
        }
    }

    internal companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
    }
}
