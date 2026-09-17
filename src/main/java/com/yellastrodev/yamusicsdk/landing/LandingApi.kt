package com.yellastrodev.yamusicsdk.landing

import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamResponseDecoder
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport
import com.yellastrodev.yamusicsdk.playlists.PlaylistApi
import com.yellastrodev.yamusicsdk.playlists.PlaylistDetails

/** Получает лендинг и раскрывает готовые персональные плейлисты через playlist API. */
internal class LandingApi(private val transport: YamTransport) {
    /** Запрашивает перечисленные блоки; допускает новые серверные типы без обновления SDK. */
    suspend fun landing(blocks: List<String>): YamResult<LandingResponse> {
        if (blocks.isEmpty() || blocks.any { it.isBlank() || ',' in it }) {
            return YamResult.Failure(YamError.InvalidResponse(
                IllegalArgumentException("Нужен непустой список отдельных типов блоков")
            ))
        }
        return when (val response = transport.execute(YamHttpRequest(
            method = YamHttpMethod.GET,
            path = "/landing3",
            query = mapOf("blocks" to blocks.distinct().joinToString(",")),
        ))) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(response.value, LandingResponse.serializer())
            is YamResult.Failure -> response
        }
    }

    /** Разбирает персональные карточки во всех блоках, сохраняя ошибки известных форматов. */
    suspend fun personalPlaylists(): YamResult<List<GeneratedPlaylist>> {
        val response = landing(listOf("personalplaylists"))
        if (response is YamResult.Failure) return response
        val playlists = mutableListOf<GeneratedPlaylist>()
        for (block in (response as YamResult.Success).value.blocks) {
            for (entity in block.entities) {
                if (entity.type != "personal-playlist") continue
                when (val decoded = YamResponseDecoder.decodeElement(entity.data, GeneratedPlaylist.serializer())) {
                    is YamResult.Success -> playlists.add(decoded.value)
                    is YamResult.Failure -> return decoded
                }
            }
        }
        return YamResult.Success(playlists)
    }

    /** Возвращает плейлист дня с треками; null означает отсутствие готовой подборки. */
    suspend fun playlistOfTheDay(): YamResult<PlaylistDetails?> {
        val response = personalPlaylists()
        if (response is YamResult.Failure) return response
        val playlist = (response as YamResult.Success).value.firstOrNull {
            it.ready && it.data != null &&
                (it.data.generatedPlaylistType ?: it.type) == "playlistOfTheDay"
        }?.data ?: return YamResult.Success(null)
        return PlaylistApi(transport).playlist(playlist.uid, playlist.kind)
    }
}
