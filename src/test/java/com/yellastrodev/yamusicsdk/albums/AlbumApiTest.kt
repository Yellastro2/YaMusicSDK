package com.yellastrodev.yamusicsdk.albums

import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamHttpResponse
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumApiTest {
    @Test
    fun withTracksUsesEndpointAndDecodesVolumes() = runBlocking {
        val transport = RecordingTransport(
            """{"result":{"id":11,"title":"Album","releaseDate":"2026-08-13","likesCount":42,"volumes":[[{"id":"10","title":"Track","available":true,"artists":[],"albums":[]}]]}}""",
        )

        val result = AlbumApi(transport).withTracks(11)

        assertTrue(result is YamResult.Success)
        val value = (result as YamResult.Success).value
        assertEquals("Album", value.title)
        assertEquals("2026-08-13", value.releaseDate)
        assertEquals(42L, value.likesCount)
        assertEquals("10", value.volumes.single().single().id)
        assertEquals(YamHttpMethod.GET, transport.request?.method)
        assertEquals("/albums/11/with-tracks", transport.request?.path)
    }

    private class RecordingTransport(private val body: String) : YamTransport {
        var request: YamHttpRequest? = null
        override suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse> {
            this.request = request
            return YamResult.Success(YamHttpResponse(200, body))
        }
    }
}
