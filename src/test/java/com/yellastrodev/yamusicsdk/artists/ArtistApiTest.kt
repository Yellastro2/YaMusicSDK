package com.yellastrodev.yamusicsdk.artists

import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamHttpResponse
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistApiTest {
    @Test
    fun briefInfoUsesEndpointAndDecodesStatsAndPopularTracks() = runBlocking {
        val transport = RecordingTransport(
            """{"result":{"artist":{"id":7,"name":"Artist"},"popularTracks":[{"id":"10","title":"Track","available":true,"artists":[],"albums":[]}],"stats":{"lastMonthListeners":1234,"lastMonthListenersDelta":12}}}""",
        )

        val result = ArtistApi(transport).briefInfo(7)

        assertTrue(result is YamResult.Success)
        val value = (result as YamResult.Success).value
        assertEquals("Artist", value.artist?.name)
        assertEquals(1234L, value.stats?.lastMonthListeners)
        assertEquals("10", value.popularTracks.single().id)
        assertEquals(YamHttpMethod.GET, transport.request?.method)
        assertEquals("/artists/7/brief-info", transport.request?.path)
    }

    private class RecordingTransport(private val body: String) : YamTransport {
        var request: YamHttpRequest? = null
        override suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse> {
            this.request = request
            return YamResult.Success(YamHttpResponse(200, body))
        }
    }
}
