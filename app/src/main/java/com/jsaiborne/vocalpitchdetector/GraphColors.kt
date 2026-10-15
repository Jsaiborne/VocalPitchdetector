package com.jsaiborne.vocalpitchdetector

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** The SharedPreferences file that holds the app's settings. */
internal const val APP_PREFS_NAME = "AppPreferences"

private const val PREF_COLOR_NOTE_LABELS = "ColorNoteLabels"
private const val PREF_COLOR_CURVE = "ColorCurve"
private const val PREF_COLOR_DOTS = "ColorDots"

/** User-chosen colours of the pitch graph elements, shared by the live and playback graphs. */
@Immutable
data class GraphColors(
    /** Stable-note marker lines and their note names. */
    val noteLabels: Color,
    val curve: Color,
    val dots: Color
) {
    companion object {
        val Default = GraphColors(
            noteLabels = Color(0xFFFFD54F),
            curve = Color(0xFF7AD3FF),
            dots = Color.White
        )
    }
}

internal data class GraphSwatch(val name: String, val color: Color)

/** Colours offered in Graph appearance; all readable on the dark graph, and they include the defaults. */
internal val GRAPH_COLOR_SWATCHES = listOf(
    GraphSwatch("White", Color(0xFFFFFFFF)),
    GraphSwatch("Sky blue", Color(0xFF7AD3FF)),
    GraphSwatch("Teal", Color(0xFF64FFDA)),
    GraphSwatch("Green", Color(0xFF81C784)),
    GraphSwatch("Lime", Color(0xFFD4E157)),
    GraphSwatch("Amber", Color(0xFFFFD54F)),
    GraphSwatch("Orange", Color(0xFFFFB74D)),
    GraphSwatch("Coral", Color(0xFFFF8A80)),
    GraphSwatch("Pink", Color(0xFFF48FB1)),
    GraphSwatch("Violet", Color(0xFFB39DDB))
)

internal fun readGraphColors(prefs: SharedPreferences): GraphColors {
    val d = GraphColors.Default
    return GraphColors(
        noteLabels = Color(prefs.getInt(PREF_COLOR_NOTE_LABELS, d.noteLabels.toArgb())),
        curve = Color(prefs.getInt(PREF_COLOR_CURVE, d.curve.toArgb())),
        dots = Color(prefs.getInt(PREF_COLOR_DOTS, d.dots.toArgb()))
    )
}

internal fun SharedPreferences.Editor.putGraphColors(colors: GraphColors): SharedPreferences.Editor =
    putInt(PREF_COLOR_NOTE_LABELS, colors.noteLabels.toArgb())
        .putInt(PREF_COLOR_CURVE, colors.curve.toArgb())
        .putInt(PREF_COLOR_DOTS, colors.dots.toArgb())
