package com.jsaiborne.vocalpitchdetector

import kotlinx.coroutines.flow.MutableStateFlow

/** What the playback notification shows. */
data class PlaybackStatus(val title: String, val isPlaying: Boolean)

/** The playback actions the notification can trigger. */
interface PlaybackControls {
    fun togglePlayPause()
    fun pause()
    fun stopPlayback()
}

/**
 * Connects the open playback screen's [PlaybackViewModel] (which owns the player) to
 * [PlaybackService] (which shows the notification and keeps playback alive in the background).
 */
object PlaybackSession {
    /** Null when no recording is open for playback; the service ends itself when it turns null. */
    val status = MutableStateFlow<PlaybackStatus?>(null)

    @Volatile
    var controls: PlaybackControls? = null
}
