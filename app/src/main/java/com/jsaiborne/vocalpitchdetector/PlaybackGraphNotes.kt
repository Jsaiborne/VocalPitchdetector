package com.jsaiborne.vocalpitchdetector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.roundToInt

/** The held notes of a recording, worked out once per recording. */
@Composable
internal fun rememberPlaybackNotes(pitchData: List<RecordedPitchPoint>): List<NoteSegment> =
    remember(pitchData) { segmentNotes(pitchData, { it.timestampMs }, { it.midiFloat }) }

/** What's being sung at the playback playhead. */
internal class PlayheadState(
    /** The recorded point at the playhead, or null when nothing is sung there. */
    val singing: RecordedPitchPoint?,
    /** The held note under the playhead, which is drawn brighter. */
    val note: NoteSegment?
) {
    /** The note being sung at the playhead, whose lane lights up. */
    val singingMidi: Int? get() = singing?.midiFloat?.roundToInt()
}

/** [nearest] is the recorded point closest to [positionMs]; it only counts if it's close enough in time. */
internal fun playheadState(positionMs: Long, nearest: RecordedPitchPoint?, notes: List<NoteSegment>): PlayheadState =
    PlayheadState(
        singing = nearest?.takeIf { abs(it.timestampMs - positionMs) <= NOTE_SILENCE_GAP_MS },
        note = notes.firstOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
    )

/** Held notes on the playback graphs, with [highlighted] (the note under the playhead) drawn brighter. */
internal fun playbackNoteStyle(pixelsPerMidi: Float, colors: GraphColors, highlighted: NoteSegment?) =
    NoteBlockStyle(
        thicknessPx = pixelsPerMidi * NOTE_BLOCK_LANE_FRACTION,
        colorFor = { note -> noteBlockColor(note, colors) },
        showNames = true,
        highlighted = highlighted
    )
