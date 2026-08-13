package com.yellastrodev.yamusicsdk.albums

import com.yellastrodev.yamusicsdk.entities.YaAlbum
import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamResponseDecoder
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport

/** Низкоуровневые операции страницы альбома. */
internal class AlbumApi(
    private val transport: YamTransport,
) {
    suspend fun withTracks(albumId: Int): YamResult<YaAlbum> {
        if (albumId <= 0) {
            return YamResult.Failure(
                YamError.InvalidResponse(
                    IllegalArgumentException("albumId должен быть положительным"),
                ),
            )
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/albums/$albumId/with-tracks",
                ),
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                YaAlbum.serializer(),
            )
            is YamResult.Failure -> response
        }
    }
}
