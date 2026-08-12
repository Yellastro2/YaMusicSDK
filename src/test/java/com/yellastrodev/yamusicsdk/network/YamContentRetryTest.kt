package com.yellastrodev.yamusicsdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException

class YamContentRetryTest {

    @Test
    fun `connect timeout повторяется один раз`() {
        var attempts = 0
        var retries = 0

        val result = retryOnceOnConnectTimeout(
            onRetry = { retries++ },
        ) {
            attempts++
            if (attempts == 1) {
                throw SocketTimeoutException("Connect timed out")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, retries)
    }

    @Test
    fun `второй connect timeout возвращается вызывающему коду`() {
        var attempts = 0
        var retries = 0

        assertThrows(SocketTimeoutException::class.java) {
            retryOnceOnConnectTimeout(
                onRetry = { retries++ },
            ) {
                attempts++
                throw SocketTimeoutException("Connect timed out")
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, retries)
    }

    @Test
    fun `read timeout не повторяется`() {
        var attempts = 0
        var retries = 0

        assertThrows(SocketTimeoutException::class.java) {
            retryOnceOnConnectTimeout(
                onRetry = { retries++ },
            ) {
                attempts++
                throw SocketTimeoutException("Read timed out")
            }
        }

        assertEquals(1, attempts)
        assertEquals(0, retries)
    }

    @Test
    fun `SOCKS SocketException с вложенным connect timeout повторяется один раз`() {
        var attempts = 0
        var retries = 0

        val result = retryOnceOnConnectTimeout(
            onRetry = { retries++ },
        ) {
            attempts++
            if (attempts == 1) {
                throw SocketException("Connect timed out").apply {
                    initCause(SocketTimeoutException("Connect timed out"))
                }
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, retries)
    }

    @Test
    fun `SOCKS SocketException с вложенным read timeout не повторяется`() {
        var attempts = 0
        var retries = 0

        assertThrows(SocketException::class.java) {
            retryOnceOnConnectTimeout(
                onRetry = { retries++ },
            ) {
                attempts++
                throw SocketException("Read timed out").apply {
                    initCause(SocketTimeoutException("Read timed out"))
                }
            }
        }

        assertEquals(1, attempts)
        assertEquals(0, retries)
    }

    @Test
    fun `запрещённая вызывающим кодом операция не повторяется`() {
        var attempts = 0
        var retries = 0

        assertThrows(SocketTimeoutException::class.java) {
            retryOnceOnConnectTimeout(
                shouldRetry = false,
                onRetry = { retries++ },
            ) {
                attempts++
                throw SocketTimeoutException("Connect timed out")
            }
        }

        assertEquals(1, attempts)
        assertEquals(0, retries)
    }
}
