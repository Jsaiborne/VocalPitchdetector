@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// --- The look shared by the exercise screens: cards, badges, the header and the pattern preview ---

/** Corners of the cards on the exercise screens. */
internal val EXERCISE_CARD_SHAPE = RoundedCornerShape(16.dp)

/** Cards and buttons stop growing at this width, so they don't stretch across a tablet or landscape. */
internal const val EXERCISE_CONTENT_MAX_WIDTH_DP = 560

/** How strongly the theme colour tints the back of a badge or a chosen option. */
internal const val TINT_ALPHA = 0.16f

/**
 * A card for one group of settings or results. With a [title] it gets a heading, with a small
 * [icon] beside it.
 */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = EXERCISE_CARD_SHAPE,
        modifier = modifier
            .widthIn(max = EXERCISE_CONTENT_MAX_WIDTH_DP.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            content()
        }
    }
}

/**
 * A small rounded badge for a value: tinted with the theme colour when [emphasized], otherwise
 * just outlined (for a value that isn't there yet).
 */
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
    style: TextStyle = MaterialTheme.typography.labelLarge
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (emphasized) colors.primary.copy(alpha = TINT_ALPHA) else Color.Transparent,
        contentColor = if (emphasized) colors.primary else colors.onSurfaceVariant,
        border = if (emphasized) null else BorderStroke(1.dp, colors.onSurfaceVariant.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = style,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Title row with a back arrow ([onBack]) or a close button ([onClose]), and any [actions] before it. */
@Composable
internal fun ExerciseHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        actions()
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close exercises")
            }
        }
    }
}

/**
 * The pattern's shape: one bar per note, placed by length and by height above the root, on a faint
 * rounded track so it reads as a small chart.
 */
@Composable
internal fun PatternPreview(exercise: Exercise, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Canvas(modifier = modifier.fillMaxWidth()) {
        drawRoundRect(track, cornerRadius = CornerRadius(6.dp.toPx()))
        val totalBeats = exercise.totalBeats
        if (totalBeats <= 0f) return@Canvas
        // The bars keep clear of the track's edges
        val inset = 5.dp.toPx()
        val width = size.width - inset * 2f
        val height = size.height - inset * 2f
        val rowHeight = height / (exercise.span + 1)
        val barHeight = rowHeight.coerceIn(1.dp.toPx(), 3.dp.toPx())
        val gap = 1.dp.toPx()
        var beat = 0f
        for (note in exercise.notes) {
            val offset = note.offset
            if (offset != null) {
                val x = inset + width * beat / totalBeats
                val w = (width * note.beats / totalBeats - gap).coerceAtLeast(gap)
                val y = inset + height - (offset - exercise.minOffset + 0.5f) * rowHeight - barHeight / 2f
                drawRoundRect(color, Offset(x, y), Size(w, barHeight), CornerRadius(barHeight / 2f))
            }
            beat += note.beats
        }
    }
}
