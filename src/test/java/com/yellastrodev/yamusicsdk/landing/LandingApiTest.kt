package com.yellastrodev.yamusicsdk.landing

import com.yellastrodev.yamusicsdk.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Проверяет вложенность лендинга, готовность подборок и двухэтапную загрузку. */
class LandingApiTest {
    /** Неизвестные карточки не мешают найти готовую подборку в следующем блоке. */
    @Test
    fun dailyPlaylistUsesReturnedOwnerAndStringKind() = runBlocking {
        val transport = FakeTransport(listOf(
            """{"blocks":[{"type":"future","entities":[{"type":"future","data":[1,2]}]},
                {"type":"personalplaylists","entities":[{"type":"personal-playlist","data":{
                "type":"playlistOfTheDay","ready":true,"data":{"uid":"9876543210","kind":"42","title":"День"}
                }}]}]}""",
            """{"playlistUuid":"uuid","uid":100,"kind":42,"title":"День","trackCount":0,
                "revision":1,"snapshot":1,"visibility":"private","collective":false,
                "isBanner":false,"isPremiere":false,"tracks":[]}""",
        ))
        val result = LandingApi(transport).playlistOfTheDay()
        assertTrue(result is YamResult.Success)
        assertEquals("День", (result as YamResult.Success).value?.playlist?.title)
        assertEquals("/landing3", transport.requests[0].path)
        assertEquals(mapOf("blocks" to "personalplaylists"), transport.requests[0].query)
        assertTrue(transport.requests[0].requiresAuthorization)
        assertEquals("/users/9876543210/playlists/42", transport.requests[1].path)
    }

    /** Отсутствующая или неготовая подборка не вызывает запрос полного плейлиста. */
    @Test
    fun missingAndUnreadyDailyReturnNull() = runBlocking {
        for (body in listOf(
            """{"blocks":[]}""",
            """{"blocks":[{"type":"personalplaylists","entities":[{"type":"personal-playlist",
                "data":{"type":"playlistOfTheDay","ready":false,"data":null}}]}]}""",
        )) {
            val transport = FakeTransport(listOf(body))
            val result = LandingApi(transport).playlistOfTheDay()
            assertTrue(result is YamResult.Success)
            assertNull((result as YamResult.Success).value)
            assertEquals(1, transport.requests.size)
        }
    }

    /** Повреждённый ответ не маскируется под отсутствие подборки. */
    @Test
    fun malformedLandingFails() = runBlocking {
        assertTrue(LandingApi(FakeTransport(listOf("{}"))).personalPlaylists() is YamResult.Failure)
    }

    /** Числовые идентификаторы и признак типа во вложенной карточке сохраняются. */
    @Test
    fun personalPlaylistDecodesNumericIds() = runBlocking {
        val transport = FakeTransport(listOf(
            """{"blocks":[{"type":"personalplaylists","entities":[{"type":"personal-playlist",
                "data":{"type":"playlistOfTheDay","ready":true,"data":{"uid":100,"kind":42,
                "title":"День","generatedPlaylistType":"playlistOfTheDay","unknown":true}}}]}]}""",
        ))
        val result = LandingApi(transport).personalPlaylists()
        assertTrue(result is YamResult.Success)
        val playlist = (result as YamResult.Success).value.single().data!!
        assertEquals("100", playlist.uid)
        assertEquals("42", playlist.kind)
        assertEquals("playlistOfTheDay", playlist.generatedPlaylistType)
    }

    /** Пустой запрос отклоняется локально, серверные ошибки сохраняются. */
    @Test
    fun validatesBlocksAndPropagatesFailure() = runBlocking {
        val transport = FakeTransport(emptyList())
        assertTrue(LandingApi(transport).landing(emptyList()) is YamResult.Failure)
        assertTrue(transport.requests.isEmpty())
        val failing = YamTransport { YamResult.Failure(YamError.Unauthorized) }
        assertEquals(YamResult.Failure(YamError.Unauthorized), LandingApi(failing).playlistOfTheDay())
    }

    /** Последовательно выдаёт JSON-фикстуры и записывает отправленные запросы. */
    private class FakeTransport(private val results: List<String>) : YamTransport {
        val requests = mutableListOf<YamHttpRequest>()

        /** Оборачивает очередную фикстуру в стандартный result API. */
        override suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse> {
            requests.add(request)
            return YamResult.Success(YamHttpResponse(200, """{"result":${results[requests.lastIndex]}}"""))
        }
    }
}
