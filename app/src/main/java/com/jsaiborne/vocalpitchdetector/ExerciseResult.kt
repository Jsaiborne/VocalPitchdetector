@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** From here up a score is good, and from [FAIR_ACCURACY] up it is fair; the colours follow. */
private const val GOOD_ACCURACY = 0.8f
private const val FAIR_ACCURACY = 0.5f

private const val RING_SIZE_DP = 168
private const val RING_STROKE_DP = 10
private const val RING_FILL_MS = 700

/** The theme colour for a good score, the accent for a fair one, the error colour for a poor one. */
@Composable
private fun accuracyColor(accuracy: Float): Color = when {
    accuracy >= GOOD_ACCURACY -> MaterialTheme.colorScheme.primary
    accuracy >= FAIR_ACCURACY -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** The score (of the whole exercise, or of what was sung before Stop), how each repetition went, and what next. */
@Suppress("LongMethod")
@Composable
internal fun ExerciseResultView(
    outcome: RunOutcome,
    newBest: Boolean,
    onReplay: () -> Unit,
    onChangeSettings: () -> Unit,
    onAllExercises: () -> Unit
) {
    val result = outcome.result
    val sungReps = result.reps.filter { it.frames > 0 }
    // Only worth pointing out when one key clearly lagged behind the rest
    val toughest = sungReps.minByOrNull { it.accuracy }
        ?.takeIf { sungReps.size > 1 && it.accuracy < result.accuracy - 0.1f }
    val tendency = tendencyText(result.meanCentsError)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (result.frames == 0) {
            Text(
                "Nothing was scored. Try a wider range or a slower tempo.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        } else {
            ScoreRing(accuracy = result.accuracy, percent = result.percent)
            if (newBest) Pill("New personal best!", style = MaterialTheme.typography.titleSmall)
            if (!outcome.completed) {
                Text(
                    "Stopped early, at key ${outcome.keysReached} of ${outcome.totalKeys}. " +
                        "Only full runs count towards your best.",
                    modifier = Modifier.widthIn(max = EXERCISE_CONTENT_MAX_WIDTH_DP.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            SectionCard {
                RepBars(reps = result.reps, startOffset = outcome.startOffset)
                Text(
                    "Each bar is one repetition, labelled with its starting note.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (toughest != null || tendency != null) {
                SectionCard {
                    toughest?.let {
                        TipRow(
                            Icons.Filled.Flag,
                            "Toughest key: starting on ${midiToDisplayName(it.rootMidi + outcome.startOffset)} " +
                                "(${(it.accuracy * 100f).roundToInt()}%)"
                        )
                    }
                    tendency?.let { TipRow(Icons.Filled.Tune, it) }
                }
            }
        }
        Column(
            modifier = Modifier.widthIn(max = EXERCISE_CONTENT_MAX_WIDTH_DP.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onReplay, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Sing it again")
            }
            OutlinedButton(onClick = onChangeSettings, modifier = Modifier.fillMaxWidth()) { Text("Change settings") }
            TextButton(onClick = onAllExercises, modifier = Modifier.fillMaxWidth()) { Text("All exercises") }
        }
    }
}

/**
 * The score as a ring that fills up to it, coloured like the bars below (good, fair or poor), with
 * the percentage in the middle.
 */
@Composable
private fun ScoreRing(accuracy: Float, percent: Int) {
    val color = accuracyColor(accuracy)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    // Starts empty and fills, so the score arrives rather than just being there
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(accuracy) { target = accuracy }
    val shown by animateFloatAsState(target, animationSpec = tween(RING_FILL_MS), label = "ScoreRing")

    Box(modifier = Modifier.size(RING_SIZE_DP.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RING_STROKE_DP.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * shown.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(
                "on pitch",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One line of advice with a small icon in front of it. */
@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp).size(20.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One bar per repetition, as tall as its accuracy, coloured from red (missed) to the theme colour,
 * and named by the note it starts on ([startOffset] semitones from its root).
 */
@Composable
private fun RepBars(reps: List<RepScore>, startOffset: Int) {
    val good = MaterialTheme.colorScheme.primary
    val fair = MaterialTheme.colorScheme.tertiary
    val poor = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val labelPaint = remember(labelColor, labelPx) {
        Paint().apply {
            color = labelColor.toArgb()
            textSize = labelPx
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        if (reps.isEmpty()) return@Canvas
        val labelSpace = labelPx * 1.5f
        val chartHeight = size.height - labelSpace
        val slot = size.width / reps.size
        val barWidth = (slot * 0.7f).coerceAtLeast(2f)
        // Label only every few bars when there are too many to fit
        val labelEvery = ceil(reps.size * labelPx * 2.6f / size.width).toInt().coerceAtLeast(1)

        for ((i, rep) in reps.withIndex()) {
            val left = i * slot + (slot - barWidth) / 2f
            val corner = CornerRadius(barWidth / 4f)
            drawRoundRect(track, Offset(left, 0f), Size(barWidth, chartHeight), corner)
            val barHeight = chartHeight * rep.accuracy
            val color = when {
                rep.accuracy >= GOOD_ACCURACY -> good
                rep.accuracy >= FAIR_ACCURACY -> fair
                else -> poor
            }
            if (barHeight > 0f) {
                drawRoundRect(color, Offset(left, chartHeight - barHeight), Size(barWidth, barHeight), corner)
            }
            if (i % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    midiToDisplayName(rep.rootMidi + startOffset),
                    left + barWidth / 2f,
                    size.height - labelPx * 0.25f,
                    labelPaint
                )
            }
        }
    }
}
