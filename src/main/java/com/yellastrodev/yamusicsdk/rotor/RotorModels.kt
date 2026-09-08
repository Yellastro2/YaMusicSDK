package com.yellastrodev.yamusicsdk.rotor

import com.yellastrodev.yamusicsdk.entities.YaTrack
import com.yellastrodev.yamusicsdk.entities.YaTrackWrap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Очередная партия треков станции Rotor.
 */
data class RotorBatch(
    val station: String,
    val batchId: String,
    val tracks: List<YaTrack>
)

/** Станция из каталога или персональных рекомендаций Rotor. */
data class RotorStation(
    val id: String,
    val name: String,
    val category: String,
    val feedbackSource: String,
    val coverUri: String? = null,
    val customName: String? = null,
    val description: String? = null
)

enum class RotorFeedbackType(internal val apiValue: String) {
    RADIO_STARTED("radioStarted"),
    TRACK_STARTED("trackStarted"),
    TRACK_FINISHED("trackFinished"),
    SKIP("skip")
}

@Serializable
internal data class RotorTracksPayload(
    val batchId: String,
    val sequence: List<YaTrackWrap> = emptyList()
)

@Serializable
internal data class RotorStationResultPayload(
    val station: RotorStationPayload? = null,
    val customName: String? = null,
    val rupDescription: String? = null
)

@Serializable
internal data class RotorDashboardPayload(
    val stations: List<RotorStationResultPayload> = emptyList()
)

/** Общая проекция станции для каталога и dashboard. */
internal fun RotorStationResultPayload.toStation(): RotorStation? {
    val value = station ?: return null
    return RotorStation(
        id = value.id.value,
        name = value.name,
        category = value.id.type,
        feedbackSource = value.idForFrom,
        coverUri = value.fullImageUrl?.takeIf { it.isNotBlank() }
            ?: value.icon?.imageUrl?.takeIf { it.isNotBlank() },
        customName = customName,
        description = rupDescription
    )
}

@Serializable
internal data class RotorStationPayload(
    val id: RotorStationIdPayload,
    val name: String = "",
    @SerialName("idForFrom")
    val idForFrom: String = "",
    val fullImageUrl: String? = null,
    val icon: RotorStationIconPayload? = null
)

@Serializable
internal data class RotorStationIconPayload(
    val imageUrl: String? = null
)

@Serializable
internal data class RotorStationIdPayload(
    val type: String,
    val tag: String
) {
    val value: String
        get() = "$type:$tag"
}
