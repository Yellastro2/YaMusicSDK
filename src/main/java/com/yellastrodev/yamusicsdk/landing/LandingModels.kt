package com.yellastrodev.yamusicsdk.landing

import com.yellastrodev.yamusicsdk.yUtils.IntOrStringAsStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Блоки главной страницы; набор зависит от пользователя и запроса. */
@Serializable
data class LandingResponse(val blocks: List<LandingBlock>)

/** Секция лендинга с исходным типом и упорядоченными карточками. */
@Serializable
data class LandingBlock(
    val type: String,
    val title: String? = null,
    val entities: List<LandingEntity> = emptyList(),
)

/** Карточка лендинга. Разнородное содержимое сохраняется без потерь в [data]. */
@Serializable
data class LandingEntity(
    val type: String,
    val id: String? = null,
    val data: JsonElement = JsonNull,
)

/** Персональная подборка: [data] может отсутствовать до готовности плейлиста. */
@Serializable
data class GeneratedPlaylist(
    val type: String,
    val ready: Boolean = false,
    val notify: Boolean = false,
    val data: LandingPlaylist? = null,
)

/** Краткая карточка плейлиста; uid и kind используются для запроса полного содержимого. */
@Serializable
data class LandingPlaylist(
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val uid: String,
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val kind: String,
    val title: String,
    val generatedPlaylistType: String? = null,
    val trackCount: Int? = null,
    val playlistUuid: String? = null,
)
