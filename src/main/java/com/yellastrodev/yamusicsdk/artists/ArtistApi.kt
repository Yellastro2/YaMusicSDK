package com.yellastrodev.yamusicsdk.artists

import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamResponseDecoder
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport

/** Низкоуровневые операции страницы артиста. */
internal class ArtistApi(
    private val transport: YamTransport,
) {
    suspend fun briefInfo(artistId: Int): YamResult<ArtistBriefInfo> {
        if (artistId <= 0) {
            return YamResult.Failure(
                YamError.InvalidResponse(
                    IllegalArgumentException("artistId должен быть положительным"),
                ),
            )
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/artists/$artistId/brief-info",
                ),
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                ArtistBriefInfo.serializer(),
            )
            is YamResult.Failure -> response
        }
    }
}
