package com.yellastrodev.yamusicsdk.network

import com.yellastrodev.yamusicsdk.download.AudioRange
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume

/**
 * Читает аудио небольшими буферами на потоке OkHttp. Отмена закрывает сетевой Call.
 * При игнорировании Range ответ 200 передаётся с offset=0, а не с запрошенной позиции.
 */
internal suspend fun Call.readAudioRange(
    start: Long,
    length: Long,
    onHeaders: (AudioRange) -> Unit,
    onBytes: (position: Long, buffer: ByteArray, count: Int) -> Unit,
): YamResult<Unit> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resume(YamResult.Failure(e.toAudioError()))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    if (response.code != 200 && response.code != 206) {
                        return@use YamResult.Failure(
                            if (response.code == 401) YamError.Unauthorized
                            else YamError.Http(response.code),
                        )
                    }
                    val body = response.body ?: throw IOException("Нет тела аудиоответа")
                    val encoding = response.header("Content-Encoding")
                    require(encoding == null || encoding.equals("identity", true)) {
                        "Неожиданное сжатие аудиоответа"
                    }
                    val range = parseAudioRange(
                        response.code, response.header("Content-Range"),
                        body.contentLength(), response.header("ETag"), start, length,
                    )
                    if (!continuation.isActive) throw IOException("Загрузка отменена")
                    onHeaders(range)
                    val buffer = ByteArray(32 * 1024)
                    var received = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            if (!continuation.isActive) throw IOException("Загрузка отменена")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            require(count.toLong() <= range.length - received) {
                                "Ответ превышает объявленный диапазон"
                            }
                            onBytes(range.offset + received, buffer, count)
                            received += count
                        }
                    }
                    if (received != range.length) throw EOFException("Аудиоответ оборван")
                    YamResult.Success(Unit)
                }
            } catch (error: IllegalArgumentException) {
                YamResult.Failure(YamError.InvalidResponse(error))
            } catch (error: Exception) {
                YamResult.Failure(error.toAudioError())
            }
            continuation.resume(result)
        }
    })
}

/** Проверяет Range до передачи первого байта потребителю. Неизвестная длина отклоняется. */
internal fun parseAudioRange(
    status: Int, contentRange: String?, contentLength: Long, entityTag: String?,
    start: Long, length: Long,
): AudioRange {
    if (status == 200) {
        require(contentLength > 0) { "Неизвестная или пустая длина аудио" }
        return AudioRange(0, contentLength, contentLength, entityTag)
    }
    require(status == 206)
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
        ?: throw IllegalArgumentException("Некорректный Content-Range")
    val first = match.groupValues[1].toLong()
    val last = match.groupValues[2].toLong()
    val total = match.groupValues[3].toLong()
    require(first == start && last >= first && total > last) { "Неверные границы аудиоответа" }
    val actualLength = last - first + 1
    require(actualLength <= length) { "Диапазон превышает запрос" }
    require(contentLength < 0 || contentLength == actualLength) { "Длины ответа расходятся" }
    return AudioRange(first, actualLength, total, entityTag)
}

private fun Exception.toAudioError(): YamError = when (this) {
    is SocketTimeoutException -> YamError.Timeout
    is UnknownHostException -> YamError.NoInternet
    else -> YamError.Network(this)
}
