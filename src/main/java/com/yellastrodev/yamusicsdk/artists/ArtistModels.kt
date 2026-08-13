package com.yellastrodev.yamusicsdk.artists

import com.yellastrodev.yamusicsdk.entities.YaArtist
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.serialization.Serializable

/** Краткая страница артиста с популярными треками и статистикой Яндекс Музыки. */
@Serializable
data class ArtistBriefInfo(
    val artist: YaArtist? = null,
    val popularTracks: List<YaTrack> = emptyList(),
    val stats: ArtistStats? = null,
)

/** Изменяемые показатели аудитории, возвращаемые Яндекс Музыкой. */
@Serializable
data class ArtistStats(
    val lastMonthListeners: Long? = null,
    val lastMonthListenersDelta: Long? = null,
)
