package com.ash.kandaloo.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

data class TrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val codec: String?,
    val isSelected: Boolean,
    val isSupported: Boolean,
    val mediaTrackGroup: TrackGroup
)

@OptIn(UnstableApi::class)
object TrackHelper {
    fun getAudioTracks(tracks: Tracks): List<TrackInfo> {
        val list = mutableListOf<TrackInfo>()
        var count = 1
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label ?: format.language ?: "Audio Track ${count++}"
                    list.add(
                        TrackInfo(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            label = label,
                            language = format.language,
                            codec = format.sampleMimeType?.substringAfter('/'),
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex),
                            mediaTrackGroup = group.mediaTrackGroup
                        )
                    )
                }
            }
        }
        return list
    }

    fun getSubtitleTracks(tracks: Tracks): List<TrackInfo> {
        val list = mutableListOf<TrackInfo>()
        var count = 1
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label ?: format.language ?: "Subtitle Track ${count++}"
                    list.add(
                        TrackInfo(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            label = label,
                            language = format.language,
                            codec = format.sampleMimeType?.substringAfter('/'),
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex),
                            mediaTrackGroup = group.mediaTrackGroup
                        )
                    )
                }
            }
        }
        return list
    }

    fun selectAudioTrack(player: ExoPlayer, track: TrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(track.mediaTrackGroup, listOf(track.trackIndex))
            )
            .build()
    }

    fun selectSubtitleTrack(player: ExoPlayer, track: TrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(track.mediaTrackGroup, listOf(track.trackIndex))
            )
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun disableSubtitles(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
}
