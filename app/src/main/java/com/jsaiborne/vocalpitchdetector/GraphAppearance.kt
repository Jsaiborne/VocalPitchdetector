package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val HIDDEN_SWATCH_ALPHA = 0.35f

/** What the Graph appearance dialog shows. */
internal data class GraphAppearanceState(
    val showGrid: Boolean,
    val showNoteLabels: Boolean,
    val showCurve: Boolean,
    val showDots: Boolean,
    val colors: GraphColors
)

/** What the Graph appearance dialog can change. */
internal data class GraphAppearanceActions(
    val onShowGridChange: (Boolean) -> Unit,
    val onShowNoteLabelsChange: (Boolean) -> Unit,
    val onShowCurveChange: (Boolean) -> Unit,
    val onShowDotsChange: (Boolean) -> Unit,
    val onColorsChange: (GraphColors) -> Unit,
    val onDismiss: () -> Unit
)

/** Show, hide and colour each graph element; the graph behind the dialog updates as you go. */
@Composable
internal fun GraphAppearanceDialog(state: GraphAppearanceState, actions: GraphAppearanceActions) {
    val colors = state.colors
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Graph appearance") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SwitchRow("Grid lines", state.showGrid, actions.onShowGridChange)
                HorizontalDivider()
                GraphElementRow(
                    "Note labels",
                    state.showNoteLabels,
                    actions.onShowNoteLabelsChange,
                    colors.noteLabels
                ) { actions.onColorsChange(colors.copy(noteLabels = it)) }
                GraphElementRow("Pitch curve", state.showCurve, actions.onShowCurveChange, colors.curve) {
                    actions.onColorsChange(colors.copy(curve = it))
                }
                GraphElementRow("Pitch dots", state.showDots, actions.onShowDotsChange, colors.dots) {
                    actions.onColorsChange(colors.copy(dots = it))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { actions.onColorsChange(GraphColors.Default) }) { Text("Reset colors") }
        }
    )
}

/** A show/hide switch with the colour swatches for that element underneath. */
@Composable
private fun GraphElementRow(
    label: String,
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    selected: Color,
    onSelect: (Color) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow(label, shown, onShownChange)
        SwatchRow(label = label, selected = selected, enabled = shown, onSelect = onSelect)
    }
}

@Composable
private fun SwatchRow(label: String, selected: Color, enabled: Boolean, onSelect: (Color) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else HIDDEN_SWATCH_ALPHA),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        for (swatch in GRAPH_COLOR_SWATCHES) {
            val isSelected = swatch.color.toArgb() == selected.toArgb()
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(swatch.color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(swatch.color) }
                    .semantics {
                        contentDescription = "$label colour: ${swatch.name}"
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    // Every swatch is a light colour, so a dark check reads on all of them
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
