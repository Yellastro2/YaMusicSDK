package com.yellastrodev.yamusicsdk.likes

import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamHttpBody
import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamHttpResponse
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LikesApiTest {

    @Test
    fun likedTracksUsesRevisionAndDecodesLibrary() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result": {
                        "library": {
                          "playlistUuid": "liked-100",
                          "uid": 100,
                          "revision": 7,
                          "tracks": []
                        }
                      }
                    }
                    """.trimIndent()
                )
            )
        )

        val result = LikesApi(transport).likedTracks(
            userId = "100",
            ifModifiedSinceRevision = 6
        )

        assertTrue(result is YamResult.Success)
        assertEquals(7, (result as YamResult.Success).value.revision)
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/users/100/likes/tracks", transport.lastRequest?.path)
        assertEquals(
            mapOf("if-modified-since-revision" to "6"),
            transport.lastRequest?.query
        )
    }

    @Test
    fun invalidLikedTracksRevisionFailsWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":{}}"""))
        )

        val result = LikesApi(transport).likedTracks("100", -1)

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastRequest)
    }

    @Test
    fun addTrackLikeSendsPythonPayloadAndDecodesRevision() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"revision":42}"""))
        )

        val result = LikesApi(transport).setTrackLiked(
            userId = "100",
            trackId = "200",
            liked = true
        )

        assertEquals(
            YamResult.Success(LikeActionResult(revision = 42)),
            result
        )
        assertEquals(YamHttpMethod.POST, transport.lastRequest?.method)
        assertEquals(
            "/users/100/likes/tracks/add-multiple",
            transport.lastRequest?.path
        )
        assertEquals(
            YamHttpBody.Form(mapOf("track-ids" to "200")),
            transport.lastRequest?.body
        )
    }

    @Test
    fun removeTrackLikeUsesRemoveAction() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"revision":43}"""))
        )

        LikesApi(transport).setTrackLiked(
            userId = "100",
            trackId = "200",
            liked = false
        )

        assertEquals(
            "/users/100/likes/tracks/remove",
            transport.lastRequest?.path
        )
    }

    @Test
    fun missingRevisionIsInvalidResponse() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"ok":true}"""))
        )

        val result = LikesApi(transport).setTrackLiked("100", "200", true)

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun transportErrorIsPreserved() = runBlocking {
        val expected = YamResult.Failure(YamError.Timeout)

        val result = LikesApi(FakeTransport(expected))
            .setTrackLiked("100", "200", true)

        assertEquals(expected, result)
    }

    @Test
    fun blankIdsFailWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"revision":1}"""))
        )

        val result = LikesApi(transport).setTrackLiked("", "200", true)

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastRequest)
    }

    @Test
    fun dislikeUsesDislikesEndpointAndDecodesRevision() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":{"revision":44}}"""))
        )

        val result = LikesApi(transport).dislikeTrack("100", "200")

        assertEquals(YamResult.Success(LikeActionResult(revision = 44)), result)
        assertEquals(YamHttpMethod.POST, transport.lastRequest?.method)
        assertEquals("/users/100/dislikes/tracks/add-multiple", transport.lastRequest?.path)
        assertEquals(YamHttpBody.Form(mapOf("track-ids" to "200")), transport.lastRequest?.body)
        // The dislikes endpoint also removes the like; no separate likes mutation is needed.
        assertEquals(1, transport.requestCount)
    }

    @Test
    fun dislikeAcceptsBareRevisionLikePythonRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"revision":45}"""))
        )
        assertEquals(
            YamResult.Success(LikeActionResult(revision = 45)),
            LikesApi(transport).dislikeTrack("100", "200"),
        )
    }

    @Test
    fun dislikeRejectsBlankIdsWithoutRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"revision":44}"""))
        )
        for ((userId, trackId) in listOf(" " to "200", "100" to " ")) {
            val result = LikesApi(transport).dislikeTrack(userId, trackId)
            assertTrue(result is YamResult.Failure)
        }
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun dislikePreservesTransportFailureAndRejectsMissingRevision() = runBlocking {
        val failure = YamResult.Failure(YamError.Timeout)
        assertEquals(failure, LikesApi(FakeTransport(failure)).dislikeTrack("100", "200"))

        val result = LikesApi(FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":{}}"""))
        )).dislikeTrack("100", "200")
        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    private class FakeTransport(
        private val result: YamResult<YamHttpResponse>
    ) : YamTransport {
        var lastRequest: YamHttpRequest? = null
        var requestCount = 0

        override suspend fun execute(
            request: YamHttpRequest
        ): YamResult<YamHttpResponse> {
            lastRequest = request
            requestCount++
            return result
        }
    }
}
