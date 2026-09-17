package com.yellastrodev.yamusicsdk.likes

import com.yellastrodev.yamusicsdk.entities.YaLikeTracklist
import kotlinx.serialization.Serializable

/**
 * Новая ревизия списка после изменения лайка или постановки «Не рекомендовать».
 */
@Serializable
data class LikeActionResult(
    val revision: Int
)

/**
 * Вложенный результат endpoint списка понравившихся треков.
 */
@Serializable
internal data class LikedTracksResult(
    val library: YaLikeTracklist
)
