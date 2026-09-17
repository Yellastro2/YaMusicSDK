package com.yellastrodev.yamusicsdk.likes

import com.yellastrodev.yamusicsdk.entities.YaLikeTracklist
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamHttpBody
import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamResponseDecoder
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport
import kotlinx.serialization.json.JsonObject

internal class LikesApi(
    private val transport: YamTransport
) {
    suspend fun likedTracks(
        userId: String,
        ifModifiedSinceRevision: Int = 0
    ): YamResult<YaLikeTracklist> {
        if (userId.isBlank() || ifModifiedSinceRevision < 0) {
            return invalidArguments(
                "userId не должен быть пустым, а ревизия — отрицательной"
            )
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/users/$userId/likes/tracks",
                    query = mapOf(
                        "if-modified-since-revision" to
                            ifModifiedSinceRevision.toString()
                    )
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response = response.value,
                    resultSerializer = LikedTracksResult.serializer()
                )
            ) {
                is YamResult.Success -> YamResult.Success(decoded.value.library)
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    suspend fun setTrackLiked(
        userId: String,
        trackId: String,
        liked: Boolean
    ): YamResult<LikeActionResult> = trackAction(userId, trackId, "likes", liked)

    /** «Не рекомендовать» также снимает лайк на стороне Яндекса. */
    suspend fun dislikeTrack(
        userId: String,
        trackId: String,
    ): YamResult<LikeActionResult> = trackAction(userId, trackId, "dislikes", true)

    private suspend fun trackAction(
        userId: String,
        trackId: String,
        collection: String,
        add: Boolean,
    ): YamResult<LikeActionResult> {
        if (userId.isBlank() || trackId.isBlank()) {
            return invalidArguments("userId и trackId не должны быть пустыми")
        }

        val action = if (add) "add-multiple" else "remove"
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/users/$userId/$collection/tracks/$action",
                    body = YamHttpBody.Form(
                        mapOf("track-ids" to trackId)
                    )
                )
            )
        ) {
            is YamResult.Success -> when (val body = YamResponseDecoder.decodeBody(
                response = response.value,
                serializer = JsonObject.serializer(),
            )) {
                is YamResult.Success -> YamResponseDecoder.decodeElement(
                    // Python Request accepts both a result envelope and a bare revision.
                    element = body.value["result"] ?: body.value,
                    serializer = LikeActionResult.serializer(),
                )
                is YamResult.Failure -> body
            }
            is YamResult.Failure -> response
        }
    }

    private fun invalidArguments(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )
}
