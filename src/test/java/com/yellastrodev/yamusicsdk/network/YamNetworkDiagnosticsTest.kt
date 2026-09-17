package com.yellastrodev.yamusicsdk.network

import com.yellastrodev.yamusicsdk.YamLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException

/** Проверяет безопасный состав диагностики без выполнения сетевых запросов. */
class YamNetworkDiagnosticsTest {
    /** Секреты из URL, заголовков и исключения не попадают в журнал сбоя TLS. */
    @Test fun `логирует этап и маршрут без секретов`() {
        val messages = mutableListOf<String>()
        val logger = object : YamLogger {
            override fun info(tag: String, message: String) { messages += message }
            override fun debug(tag: String, message: String) { messages += message }
            override fun warning(tag: String, message: String) { messages += message }
            override fun error(tag: String, message: String, cause: Throwable?) { messages += message }
        }
        val call = OkHttpClient().newCall(Request.Builder()
            .url("https://audio.example/private-signature/file?token=secret-query")
            .header("Authorization", "OAuth secret-header")
            .header("Range", "bytes=0-262143")
            .build())
        val listener = YamNetworkDiagnostics(logger)
        listener.callStart(call)
        listener.connectStart(call, InetSocketAddress.createUnresolved("proxy.example", 1080),
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.example", 1080)))
        listener.secureConnectStart(call)
        listener.callFailed(call, SocketTimeoutException("secret-exception"))
        val output = messages.joinToString("\n")
        assertTrue(output.contains("хост=audio.example"))
        assertTrue(output.contains("SOCKS proxy.example:1080"))
        assertTrue(output.contains("этап=TLS"))
        assertTrue(output.contains("SocketTimeoutException"))
        assertTrue(output.contains("bytes=0-262143"))
        assertFalse(output.contains("secret"))
        assertFalse(output.contains("private-signature"))
    }
}
