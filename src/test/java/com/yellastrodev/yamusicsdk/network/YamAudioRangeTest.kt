package com.yellastrodev.yamusicsdk.network

import com.sun.net.httpserver.HttpServer
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YamAudioRangeTest {
    @Test fun `проверяет смещение длину и игнорирование Range`() {
        assertEquals(10L, parseAudioRange(206, "bytes 10-19/100", 10, null, 10, 10).offset)
        assertEquals(0L, parseAudioRange(200, null, 100, null, 50, 10).offset)
        assertThrows(IllegalArgumentException::class.java) {
            parseAudioRange(206, "bytes 0-9/100", 10, null, 10, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAudioRange(206, "bytes 10-19/100", 9, null, 10, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAudioRange(206, "bytes 10-109/100", 100, null, 10, 100)
        }
    }

    @Test fun `реальный HTTP передает Range без OAuth и читает байты`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var rangeHeader: String? = null
        var authorization: String? = null
        server.createContext("/audio") { exchange ->
            rangeHeader = exchange.requestHeaders.getFirst("Range")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Content-Range", "bytes 10-13/20")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write(byteArrayOf(10, 11, 12, 13)) }
        }
        server.start()
        val transport = YamHttpTransport(accessToken = { "test-token" }, logger = NoOpYamLogger)
        try {
            val received = mutableListOf<Byte>()
            val result = withTimeout(5_000) {
                transport.audioRange("http://127.0.0.1:${server.address.port}/audio", 10, 4,
                    { assertEquals(20L, it.totalLength) },
                    { position, bytes, count ->
                        assertEquals(10L + received.size, position)
                        received.addAll(bytes.take(count))
                    })
            }
            assertTrue(result is YamResult.Success)
            assertEquals("bytes=10-13", rangeHeader)
            assertNull(authorization)
            assertEquals(listOf<Byte>(10, 11, 12, 13), received)
        } finally {
            transport.close()
            server.stop(0)
        }
    }

    @Test fun `отмена не ждет зависший HTTP body`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val release = CountDownLatch(1)
        server.createContext("/audio") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.use {
                it.write(1)
                it.flush()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        server.start()
        val transport = YamHttpTransport(accessToken = { "" }, logger = NoOpYamLogger)
        try {
            val firstByte = CompletableDeferred<Unit>()
            val job = launch {
                transport.audioRange("http://127.0.0.1:${server.address.port}/audio", 0, 100,
                    {}, { _, _, _ -> firstByte.complete(Unit) })
            }
            withTimeout(3_000) { firstByte.await() }
            withTimeout(1_000) { job.cancelAndJoin() }
        } finally {
            release.countDown()
            transport.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
