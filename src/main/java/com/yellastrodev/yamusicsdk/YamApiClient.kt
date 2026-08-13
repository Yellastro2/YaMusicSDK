package com.yellastrodev.yamusicsdk

import com.yellastrodev.yamusicsdk.account.AccountApi
import com.yellastrodev.yamusicsdk.account.AccountStatus
import com.yellastrodev.yamusicsdk.albums.AlbumApi
import com.yellastrodev.yamusicsdk.artists.ArtistApi
import com.yellastrodev.yamusicsdk.artists.ArtistBriefInfo
import com.yellastrodev.yamusicsdk.covers.CoverApi
import com.yellastrodev.yamusicsdk.download.DownloadApi
import com.yellastrodev.yamusicsdk.download.DownloadInfo
import com.yellastrodev.yamusicsdk.entities.CoverSize
import com.yellastrodev.yamusicsdk.entities.YaLikeTracklist
import com.yellastrodev.yamusicsdk.entities.YaAlbum
import com.yellastrodev.yamusicsdk.entities.YaPlaylist
import com.yellastrodev.yamusicsdk.entities.YaTrack
import com.yellastrodev.yamusicsdk.likes.LikeActionResult
import com.yellastrodev.yamusicsdk.likes.LikesApi
import com.yellastrodev.yamusicsdk.network.YamConnectionFactory
import com.yellastrodev.yamusicsdk.network.YamHttpTransport
import com.yellastrodev.yamusicsdk.network.YamProxyConfig
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.playlists.PlaylistApi
import com.yellastrodev.yamusicsdk.playlists.PlaylistDetails
import com.yellastrodev.yamusicsdk.playlists.PlaylistVisibility
import com.yellastrodev.yamusicsdk.rotor.RotorApi
import com.yellastrodev.yamusicsdk.rotor.RotorBatch
import com.yellastrodev.yamusicsdk.rotor.RotorFeedbackType
import com.yellastrodev.yamusicsdk.search.SearchApi
import com.yellastrodev.yamusicsdk.search.SearchResponse
import com.yellastrodev.yamusicsdk.search.SearchSuggestions
import com.yellastrodev.yamusicsdk.search.SearchType
import com.yellastrodev.yamusicsdk.tracks.TrackApi
import com.yellastrodev.yamusicsdk.tracks.PlayAudioRequest
import java.io.OutputStream

/**
 * Корутино-ориентированный клиент API Яндекс Музыки для Kotlin/JVM.
 *
 * Публичные сетевые операции возвращают [YamResult] и не отдают JSON наружу.
 */
class YamApiClient(
    accessToken: String,
    userId: String,
    login: String = "",
    val logger: YamLogger = NoOpYamLogger,
    proxyConfig: YamProxyConfig? = null,
) : AutoCloseable {
    @Volatile
    private var accessToken: String = accessToken

    @Volatile
    var userId: String = userId
        private set

    @Volatile
    var login: String = login
        private set

    private val httpTransportDelegate = lazy {
        YamHttpTransport(
            accessToken = { this@YamApiClient.accessToken },
            connectionFactory = YamConnectionFactory(
                proxyConfig,
                logger,
            ),
            logger = logger,
        )
    }
    private val httpTransport by httpTransportDelegate
    private val accountApi by lazy { AccountApi(httpTransport) }
    private val albumApi by lazy { AlbumApi(httpTransport) }
    private val artistApi by lazy { ArtistApi(httpTransport) }
    private val likesApi by lazy { LikesApi(httpTransport) }
    private val playlistApi by lazy { PlaylistApi(httpTransport) }
    private val trackApi by lazy { TrackApi(httpTransport) }
    private val searchApi by lazy { SearchApi(httpTransport) }
    private val rotorApi by lazy { RotorApi(httpTransport) }
    private val downloadApi by lazy {
        DownloadApi(httpTransport, httpTransport)
    }
    private val coverApi by lazy { CoverApi(httpTransport) }

    /**
     * Применяет прокси ко всем новым API-, content- и redirect-соединениям клиента.
     * Уже открытое соединение продолжает работу с прежней конфигурацией.
     */
    fun updateProxyConfig(
        proxyConfig: YamProxyConfig?,
    ) {
        httpTransport.updateProxyConfig(
            proxyConfig,
        )

        logger.info(
            TAG,
            "[updateProxyConfig] Конфигурация прокси обновлена",
        )
    }

    fun updateAuthorization(
        token: String,
        userId: String,
        login: String = ""
    ) {
        accessToken = token
        this.userId = userId
        this.login = login
        logger.info(TAG, "[updateAuthorization] Авторизация клиента обновлена")
    }

    fun clearAuthorization() {
        accessToken = ""
        userId = ""
        login = ""
        logger.info(TAG, "[clearAuthorization] Авторизация клиента очищена")
    }

    suspend fun accountStatus(): YamResult<AccountStatus> =
        accountApi.status()

    suspend fun setTrackLiked(
        trackId: String,
        liked: Boolean,
        userId: String = this.userId
    ): YamResult<LikeActionResult> = likesApi.setTrackLiked(
        userId = userId,
        trackId = trackId,
        liked = liked
    )

    suspend fun likedTracks(
        ifModifiedSinceRevision: Int = 0,
        userId: String = this.userId
    ): YamResult<YaLikeTracklist> = likesApi.likedTracks(
        userId = userId,
        ifModifiedSinceRevision = ifModifiedSinceRevision
    )

    suspend fun playlist(
        kind: Int,
        userId: String = this.userId
    ): YamResult<PlaylistDetails> =
        playlistApi.playlist(userId, kind.toString())

    suspend fun playlists(
        userId: String = this.userId
    ): YamResult<List<YaPlaylist>> = playlistApi.playlists(userId)

    suspend fun createPlaylist(
        title: String,
        isPublic: Boolean = true,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.create(
        userId = userId,
        title = title,
        visibility = if (isPublic) {
            PlaylistVisibility.PUBLIC
        } else {
            PlaylistVisibility.PRIVATE
        }
    )

    suspend fun deletePlaylist(
        kind: String,
        userId: String = this.userId
    ): YamResult<Unit> = playlistApi.delete(userId, kind)

    suspend fun addTrack(
        playlistKind: Int,
        revision: Int,
        trackId: String,
        trackAlbum: String,
        at: Int = 0,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.insertTrack(
        userId = userId,
        kind = playlistKind.toString(),
        revision = revision,
        trackId = trackId,
        albumId = trackAlbum,
        at = at
    )

    suspend fun removeTrack(
        playlistKind: Int,
        revision: Int,
        trackNumber: Int,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.deleteTrack(
        userId = userId,
        kind = playlistKind.toString(),
        revision = revision,
        fromIndex = trackNumber,
        toIndex = trackNumber + 1
    )

    suspend fun tracks(
        trackIds: List<String>,
        withPositions: Boolean = true
    ): YamResult<List<YaTrack>> =
        trackApi.tracks(trackIds, withPositions)

    /** Возвращает альбом вместе с разбитыми по дискам треками. */
    suspend fun albumWithTracks(albumId: Int): YamResult<YaAlbum> =
        albumApi.withTracks(albumId)

    /** Возвращает профиль артиста, его популярные треки и статистику аудитории. */
    suspend fun artistBriefInfo(artistId: Int): YamResult<ArtistBriefInfo> =
        artistApi.briefInfo(artistId)

    /** Выполняет поиск по Яндекс Музыке и возвращает типизированные известные разделы выдачи. */
    suspend fun search(
        text: String,
        nocorrect: Boolean = false,
        type: SearchType = SearchType.ALL,
        page: Int = 0,
        playlistInBest: Boolean = true,
    ): YamResult<SearchResponse> = searchApi.search(
        text = text,
        nocorrect = nocorrect,
        type = type,
        page = page,
        playlistInBest = playlistInBest,
    )

    /** Возвращает подсказки для введённой части поискового запроса. */
    suspend fun searchSuggestions(part: String): YamResult<SearchSuggestions> =
        searchApi.suggestions(part)

    /**
     * Отправляет универсальную телеметрию прослушивания трека.
     * Для волны вызывается параллельно специализированным rotor feedback.
     */
    suspend fun playAudio(
        request: PlayAudioRequest
    ): YamResult<Unit> = trackApi.playAudio(
        request.copy(
            uid = request.uid ?: userId.toLongOrNull()
        )
    )

    suspend fun startWave(station: String): YamResult<RotorBatch> =
        rotorApi.tracks(station = station)

    suspend fun nextWaveTracks(
        station: String,
        previousTrackId: String
    ): YamResult<RotorBatch> = rotorApi.tracks(
        station = station,
        queue = previousTrackId
    )

    suspend fun sendWaveStarted(
        station: String,
        batchId: String? = null,
        from: String? = null
    ): YamResult<Unit> {
        val resolvedSource = from ?: when (
            val sourceResult = rotorApi.feedbackSource(station)
        ) {
            is YamResult.Success -> sourceResult.value
            is YamResult.Failure -> fallbackFeedbackSource(station)
        }
        return rotorApi.feedback(
            station = station,
            type = RotorFeedbackType.RADIO_STARTED,
            from = resolvedSource,
            batchId = batchId
        )
    }

    /**
     * Повторяет выбор `station.id_for_from` из radio-примера Python SDK.
     */
    private fun fallbackFeedbackSource(station: String): String {
        val stationType = station.substringBefore(':')
        return if (stationType == USER_STATION_TYPE) {
            "$USER_STATION_TYPE-$userId"
        } else {
            stationType
        }
    }

    suspend fun sendWaveTrackStarted(
        station: String,
        trackId: String,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.TRACK_STARTED,
        trackId = trackId,
        batchId = batchId
    )

    suspend fun sendWaveTrackFinished(
        station: String,
        trackId: String,
        totalPlayedSeconds: Float,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.TRACK_FINISHED,
        trackId = trackId,
        totalPlayedSeconds = totalPlayedSeconds,
        batchId = batchId
    )

    suspend fun sendWaveTrackSkipped(
        station: String,
        trackId: String,
        totalPlayedSeconds: Float,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.SKIP,
        trackId = trackId,
        totalPlayedSeconds = totalPlayedSeconds,
        batchId = batchId
    )

    suspend fun trackDownloadInfo(
        trackId: String
    ): YamResult<List<DownloadInfo>> =
        downloadApi.downloadInfo(trackId)

    suspend fun trackDownloadUrl(
        trackId: String
    ): YamResult<String> =
        downloadApi.directDownloadUrl(trackId)

    suspend fun trackDownloadBytes(
        trackId: String
    ): YamResult<ByteArray> =
        downloadApi.downloadBytes(trackId)

    /** Потоково загружает трек, сохраняя поддержку настроенного ЯМ-прокси. */
    suspend fun trackDownloadTo(
        trackId: String,
        output: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): YamResult<Long> =
        downloadApi.downloadTo(
            trackId = trackId,
            output = output,
            onProgress = onProgress,
        )

    suspend fun coverBytes(
        uri: String,
        size: CoverSize
    ): YamResult<ByteArray> = coverApi.bytes(uri, size)

    /** Освобождает сетевые ресурсы и регистрацию SOCKS5-аутентификации клиента. */
    override fun close() {
        if (httpTransportDelegate.isInitialized()) {
            httpTransport.close()
        }
    }

    private companion object {
        const val TAG = "YamApiClient"
        const val USER_STATION_TYPE = "user"
    }
}
