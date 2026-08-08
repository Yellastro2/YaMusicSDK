package com.yellastrodev.yamusicsdk.entities

import kotlinx.serialization.Serializable

/**
 * "tracks" : [ {
 *     "id" : 35699143,
 *     "originalIndex" : 0,
 *     "timestamp" : "2025-04-04T20:04:28+00:00",
 *     "track" : {
 *       "id" : "35699143",
 *       "realId" : "35699143",
 *       "title" : "Это секс",
 */
@Serializable
class YaTrackList (
    val tracks: List<YaTrackWrap>
)

@Serializable
class YaTrackWrap (
    val track: YaTrack
)