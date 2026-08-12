package com.yellastrodev.yamusicsdk.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class YamSocks5ProxyTest {

    @Test
    fun `SOCKS authenticator ограничен протоколом и адресом proxy`() {
        val original = Authenticator.getDefault()
        val fallback = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication("fallback", "secret".toCharArray())
        }
        Authenticator.setDefault(fallback)

        val registration = YamSocksAuthenticatorRegistry.register(
            YamProxyConfig(
                host = "proxy.example",
                port = 1_080,
                type = YamProxyType.SOCKS,
                username = "yam-user",
                password = "yam-password",
            ),
        )

        try {
            val socksCredentials = Authenticator.requestPasswordAuthentication(
                "proxy.example",
                InetAddress.getLoopbackAddress(),
                1_080,
                "SOCKS5",
                "SOCKS authentication",
                null,
            )
            assertEquals("yam-user", socksCredentials?.userName)
            assertEquals("yam-password", socksCredentials?.password?.concatToString())

            val unrelatedCredentials = Authenticator.requestPasswordAuthentication(
                "proxy.example",
                InetAddress.getLoopbackAddress(),
                1_080,
                "HTTP",
                "Server authentication",
                "Basic",
            )
            assertEquals("fallback", unrelatedCredentials?.userName)

            registration.close()
            assertSame(fallback, Authenticator.getDefault())
            val releasedCredentials = Authenticator.requestPasswordAuthentication(
                "proxy.example",
                InetAddress.getLoopbackAddress(),
                1_080,
                "SOCKS5",
                "SOCKS authentication",
                null,
            )
            assertEquals("fallback", releasedCredentials?.userName)
        } finally {
            registration.close()
            Authenticator.setDefault(original)
        }
    }

    @Test
    fun `OkHttp выполняет SOCKS5 handshake с авторизацией и proxy DNS`() {
        val authenticatorBefore = Authenticator.getDefault()
        FakeSocks5Server(
            expectedUsername = "yam-user",
            expectedPassword = "yam-password",
        ).use { proxyServer ->
            val clients = YamConnectionFactory(
                YamProxyConfig(
                    host = "127.0.0.1",
                    port = proxyServer.port,
                    type = YamProxyType.SOCKS,
                    username = "yam-user",
                    password = "yam-password",
                ),
            ).createClients(
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 2_000,
            )

            try {
                clients.regular.newCall(
                    Request.Builder()
                        .url("http://music.test/proxy-check")
                        .build(),
                ).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body?.string())
                }
            } finally {
                clients.close()
            }

            val request = proxyServer.awaitRequest()
            assertEquals("yam-user", request.username)
            assertEquals("yam-password", request.password)
            assertEquals("music.test", request.destinationHost)
            assertEquals(80, request.destinationPort)
        }
        awaitCondition {
            Authenticator.getDefault() === authenticatorBefore
        }
    }

    @Test
    fun `SOCKS5 без авторизации не устанавливает глобальный authenticator`() {
        val before = Authenticator.getDefault()
        val clients = YamConnectionFactory(
            YamProxyConfig(
                host = "127.0.0.1",
                port = 1_080,
                type = YamProxyType.SOCKS,
            ),
        ).createClients(
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
        )

        try {
            assertSame(before, Authenticator.getDefault())
            assertNull(
                clients.regular.proxyAuthenticator.authenticate(
                    null,
                    okhttp3.Response.Builder()
                        .request(
                            Request.Builder()
                                .url("https://api.music.yandex.net")
                                .build(),
                        )
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(407)
                        .message("Proxy authentication")
                        .build(),
                ),
            )
        } finally {
            clients.close()
        }
    }

    private class FakeSocks5Server(
        private val expectedUsername: String,
        private val expectedPassword: String,
    ) : AutoCloseable {

        private val serverSocket = ServerSocket(
            0,
            1,
            InetAddress.getLoopbackAddress(),
        )
        private val request = CompletableFuture<ObservedRequest>()
        private val worker = thread(
            name = "yam-socks5-test",
            isDaemon = true,
        ) {
            runCatching {
                serverSocket.accept().use(::handle)
            }.onFailure(request::completeExceptionally)
        }

        val port: Int
            get() = serverSocket.localPort

        fun awaitRequest(): ObservedRequest =
            request.get(3, TimeUnit.SECONDS)

        override fun close() {
            serverSocket.close()
            worker.join(1_000)
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 3_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            require(input.readRequired() == SOCKS_VERSION)
            val methodCount = input.readRequired()
            val methods = ByteArray(methodCount).also { bytes ->
                input.readFully(bytes)
            }
            require(USERNAME_PASSWORD_METHOD.toByte() in methods)
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), USERNAME_PASSWORD_METHOD.toByte()))
            output.flush()

            require(input.readRequired() == AUTH_VERSION)
            val username = input.readString(input.readRequired())
            val password = input.readString(input.readRequired())
            val authAccepted =
                username == expectedUsername && password == expectedPassword
            output.write(
                byteArrayOf(
                    AUTH_VERSION.toByte(),
                    if (authAccepted) AUTH_SUCCESS.toByte() else AUTH_FAILURE.toByte(),
                ),
            )
            output.flush()
            require(authAccepted)

            require(input.readRequired() == SOCKS_VERSION)
            require(input.readRequired() == CONNECT_COMMAND)
            input.readRequired()
            val destinationHost = when (input.readRequired()) {
                ADDRESS_TYPE_IPV4 ->
                    ByteArray(4).also { bytes ->
                        input.readFully(bytes)
                    }
                        .joinToString(".") { byte ->
                            (byte.toInt() and 0xff).toString()
                        }
                ADDRESS_TYPE_DOMAIN ->
                    input.readString(input.readRequired())
                ADDRESS_TYPE_IPV6 ->
                    InetAddress.getByAddress(
                        ByteArray(16).also { bytes ->
                            input.readFully(bytes)
                        },
                    ).hostAddress
                else -> error("Неизвестный SOCKS5 address type")
            }
            val destinationPort =
                (input.readRequired() shl 8) or input.readRequired()

            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(),
                    REQUEST_SUCCESS.toByte(),
                    0,
                    ADDRESS_TYPE_IPV4.toByte(),
                    127,
                    0,
                    0,
                    1,
                    0,
                    0,
                ),
            )
            output.flush()

            input.readHttpHeaders()
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()

            request.complete(
                ObservedRequest(
                    username = username,
                    password = password,
                    destinationHost = destinationHost,
                    destinationPort = destinationPort,
                ),
            )
        }

        private fun BufferedInputStream.readRequired(): Int =
            read().takeIf { it >= 0 } ?: throw EOFException()

        private fun BufferedInputStream.readFully(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException()
                offset += count
            }
        }

        private fun BufferedInputStream.readString(length: Int): String =
            ByteArray(length)
                .also { bytes -> readFully(bytes) }
                .toString(StandardCharsets.ISO_8859_1)

        private fun BufferedInputStream.readHttpHeaders() {
            var matched = 0
            while (matched < HTTP_HEADER_END.size) {
                val value = readRequired().toByte()
                matched = if (value == HTTP_HEADER_END[matched]) {
                    matched + 1
                } else {
                    0
                }
            }
        }

        private companion object {
            const val SOCKS_VERSION = 5
            const val AUTH_VERSION = 1
            const val USERNAME_PASSWORD_METHOD = 2
            const val AUTH_SUCCESS = 0
            const val AUTH_FAILURE = 1
            const val CONNECT_COMMAND = 1
            const val REQUEST_SUCCESS = 0
            const val ADDRESS_TYPE_IPV4 = 1
            const val ADDRESS_TYPE_DOMAIN = 3
            const val ADDRESS_TYPE_IPV6 = 4
            val HTTP_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        }
    }

    private data class ObservedRequest(
        val username: String,
        val password: String,
        val destinationHost: String,
        val destinationPort: Int,
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        check(condition()) {
            "Условие не выполнилось за отведённое время"
        }
    }
}
