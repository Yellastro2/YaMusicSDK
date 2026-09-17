package com.yellastrodev.yamusicsdk.network

import com.yellastrodev.yamusicsdk.YamLogger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/** Диагностика этапов одного HTTP-вызова без URL-путей, заголовков авторизации и текстов исключений. */
internal class YamNetworkDiagnostics(private val logger: YamLogger) : EventListener() {
    private val id = ids.incrementAndGet()
    private val started = System.nanoTime()
    private var stage = "очередь"
    private var stageStarted = started
    private var host = "неизвестен"
    private var route = "ещё не выбран"

    /** Фиксирует начало этапа, не создавая отдельный лог на каждый сетевой callback. */
    private fun stage(value: String) {
        stage = value
        stageStarted = System.nanoTime()
    }

    /** Печатает безопасный контекст и время последнего этапа. */
    private fun report(event: String, details: String = "", failure: Boolean = false) {
        val now = System.nanoTime()
        val message = "[$event] Запрос=$id, хост=$host, маршрут=$route, этап=$stage, " +
            "всего=${(now - started) / 1_000_000}мс, этап=${(now - stageStarted) / 1_000_000}мс $details"
        if (failure) logger.warning("YamNetwork", message) else logger.debug("YamNetwork", message)
    }

    /** Связывает запрос с хостом и числовым диапазоном без раскрытия подписанной ссылки. */
    override fun callStart(call: Call) {
        host = call.request().url.host
        val range = call.request().header("Range")?.takeIf { it.matches(Regex("bytes=\\d+-\\d+")) }
        report("networkStart", "диапазон=${range ?: "нет"}")
    }

    /** Отмечает ожидание DNS. */
    override fun dnsStart(call: Call, domainName: String) { stage("DNS: $domainName") }

    /** Отмечает завершение DNS; фактический адрес будет записан при подключении. */
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { stage("DNS завершён") }

    /** Сохраняет адрес сокета и тип прокси, без учётных данных. */
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        route = "${proxy.type()} ${inetSocketAddress.hostString}:${inetSocketAddress.port}"
        stage("подключение TCP/прокси")
    }

    /** Отмечает начало TLS. */
    override fun secureConnectStart(call: Call) { stage("TLS") }

    /** Отмечает завершение TLS. */
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { stage("TLS завершён") }

    /** Показывает неудачную попытку подключения, даже если OkHttp затем сменит маршрут. */
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        report("networkConnectFailed", "ошибка=${ioe.javaClass.simpleName}, причина=${ioe.cause?.javaClass?.simpleName ?: "нет"}", true)
    }

    /** Учитывает в том числе повторное использование соединения из пула. */
    override fun connectionAcquired(call: Call, connection: Connection) {
        val selected = connection.route()
        route = "${selected.proxy.type()} ${selected.socketAddress.hostString}:${selected.socketAddress.port}"
        stage("соединение получено")
    }

    /** Отмечает отправку заголовков. */
    override fun requestHeadersStart(call: Call) { stage("отправка заголовков") }

    /** Обновляет хост после redirect и отмечает ожидание ответа. */
    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
        host = request.url.host
        stage("ожидание ответа")
    }

    /** Отмечает ожидание заголовков сервера. */
    override fun responseHeadersStart(call: Call) { stage("ожидание заголовков ответа") }

    /** Записывает статус ответа до чтения тела. */
    override fun responseHeadersEnd(call: Call, response: Response) {
        report("networkHeaders", "HTTP=${response.code}")
        stage("ожидание тела ответа")
    }

    /** Отмечает начало чтения тела; это событие ещё не гарантирует получение первого байта. */
    override fun responseBodyStart(call: Call) { stage("чтение тела ответа") }

    /** Записывает количество прочитанных сетевым клиентом байтов. */
    override fun responseBodyEnd(call: Call, byteCount: Long) { report("networkBody", "прочитано=$byteCount байт") }

    /** Записывает сбой до потери исходного типа исключения в YamError. */
    override fun callFailed(call: Call, ioe: IOException) {
        report("networkFailed", "отменён=${call.isCanceled()}, ошибка=${ioe.javaClass.simpleName}, причина=${ioe.cause?.javaClass?.simpleName ?: "нет"}", true)
    }

    private companion object {
        val ids = AtomicLong()
    }
}
