package com.yellastrodev.yamusicsdk.network

import okhttp3.Credentials
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class YamConnectionFactoryTest {

    @Test
    fun `без конфигурации фабрика не задаёт явный прокси`() {
        val factory = YamConnectionFactory()

        assertNull(factory.proxy)
    }

    @Test
    fun `HTTP конфигурация применяется к фабрике`() {
        val factory = YamConnectionFactory(
            YamProxyConfig(
                host = "proxy.example",
                port = 8_080,
            ),
        )

        assertEquals(Proxy.Type.HTTP, factory.proxy?.type())
        assertEquals(
            InetSocketAddress.createUnresolved("proxy.example", 8_080),
            factory.proxy?.address(),
        )
    }

    @Test
    fun `HTTP credentials возвращаются для превентивного CONNECT`() {
        val factory =
            YamConnectionFactory(
                YamProxyConfig(
                    host = "proxy.example",
                    port = 8_080,
                    username = "user",
                    password = ",password",
                ),
            )

        val client =
            factory.createClient(
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 15_000,
                followRedirects = true,
            )

        val challenge =
            Response.Builder()
                .request(
                    Request.Builder()
                        .url("https://api.music.yandex.net")
                        .build(),
                )
                .protocol(Protocol.HTTP_1_1)
                .code(407)
                .message("Preemptive proxy authentication")
                .header(
                    "Proxy-Authenticate",
                    "OkHttp-Preemptive",
                )
                .build()

        val authenticatedRequest =
            client.proxyAuthenticator.authenticate(
                null,
                challenge,
            )

        assertEquals(
            Credentials.basic(
                "user",
                ",password",
            ),
            authenticatedRequest?.header(
                "Proxy-Authorization",
            ),
        )

        val repeatedChallenge =
            challenge
                .newBuilder()
                .request(authenticatedRequest!!)
                .build()

        assertNull(
            client.proxyAuthenticator.authenticate(
                null,
                repeatedChallenge,
            ),
        )
    }

    @Test
    fun `некорректный порт отклоняется`() {
        assertThrows(IllegalArgumentException::class.java) {
            YamProxyConfig(
                host = "proxy.example",
                port = 0,
            )
        }
    }
}
