package com.example.vocalpitchdetector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.log2
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.graphics.nativeCanvas

private data class PitchSample(val tMs: Long, val freq: Float, val midi: Float)
private data class StableMarker(val tMs: Long, val midi: Int)

private fun freqToMidiLocal(f: Double): Double = 69.0 + 12.0 * log2(f / 440.0)
private fun midiToNoteNameLocal(midi: Int): String {
    val names = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}

@Composable
fun PitchGraphCard(
    engine: PitchEngine,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onTogglePause: () -> Unit,
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    blackKeyShiftFraction: Float = 0.5f,
    timeWindowMs: Long = 8000L
) {
    // UI toggles for optional visuals
    var showNoteLabels by remember { mutableStateOf(true) }
    var showHorizontalGrid by remember { mutableStateOf(true) }
    var showCurve by remember { mutableStateOf(true) }

    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Live Pitch Graph", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

                val freqDisplay = remember { mutableStateOf("-- Hz") }
                LaunchedEffect(engine) {
                    engine.state.collectLatest { s ->
                        freqDisplay.value = if (s.frequency > 0f) "${String.format("%.1f", s.frequency)} Hz" else "--"
                    }
                }

                Text(text = freqDisplay.value)
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onTogglePause) { Text(if (paused) "Resume" else "Hold") }

                // Compact "Options" dropdown so toggles don't steal layout space
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Graph options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Note labels", modifier = Modifier.weight(1f))
                            Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                        }
                    }, onClick = { /* no-op; switch handles state */ })

                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Grid lines", modifier = Modifier.weight(1f))
                            Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                        }
                    }, onClick = { /* no-op */ })

                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Curve", modifier = Modifier.weight(1f))
                            Switch(checked = showCurve, onCheckedChange = { showCurve = it })
                        }
                    }, onClick = { /* no-op */ })
                }
            }

            Box(modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)) {
                PitchGraph(
                    engine = engine,
                    paused = paused,
                    modifier = Modifier.fillMaxSize(),
                    windowMs = timeWindowMs,
                    startMidi = startMidi,
                    endMidi = endMidi,
                    whiteKeyWidthDp = whiteKeyWidthDp,
                    scrollState = scrollState,
                    alignmentOffsetDp = alignmentOffsetDp,
                    blackKeyShiftFraction = blackKeyShiftFraction,
                    showNoteLabels = showNoteLabels,
                    showHorizontalGrid = showHorizontalGrid,
                    showCurve = showCurve
                )
            }
        }
    }
}

@Composable
fun PitchGraph(
    engine: PitchEngine,
    paused: Boolean = false,
    modifier: Modifier = Modifier,
    windowMs: Long = 8000L, // time window shown in ms
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    blackKeyShiftFraction: Float = 0.5f,
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true
) {
    val samples = remember { mutableStateListOf<PitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    // collect live pitch state into samples (respect paused)
    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(PitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMs
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    // stable note events
    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMs
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

    // paints
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val smallPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 18f
            isAntiAlias = true
        }
    }
    val yellowPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 18f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    // content width based on white key count and requested whiteKeyWidth
    val whiteCount = (startMidi..endMidi).count { !midiToNoteNameLocal(it).contains("#") }
    val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
    val contentWidthPx = whiteCount * whiteKeyWidthPx
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    // use provided scroll state or a local one
    val sState = scrollState ?: rememberScrollState()

    Box(modifier = modifier) {
        Box(modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(sState)
        ) {
            Canvas(modifier = Modifier
                .width(contentWidthDp)
                .fillMaxHeight()
            ) {
                val w = size.width
                val h = size.height

                // background
                drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF081226), Color(0xFF0F2A3F))), size = Size(w, h))

                val padTop = 12f
                val padBottom = 20f
                val padLeft = 6f
                val padRight = 6f
                val innerH = h - padTop - padBottom
                val innerW = w - padLeft - padRight

                val minMidi = startMidi
                val maxMidi = endMidi

                // Build white/black key indices (so mapping matches Piano exactly)
                val whiteList = mutableListOf<Int>()
                for (m in minMidi..maxMidi) if (!midiToNoteNameLocal(m).contains("#")) whiteList.add(m)
                val whiteIndexMap = mutableMapOf<Int, Int>()
                whiteList.forEachIndexed { idx, midi -> whiteIndexMap[midi] = idx }

                val blackLeftIndexMap = mutableMapOf<Int, Int>()
                var whiteIdxCounter = 0
                for (m in minMidi..maxMidi) {
                    val name = midiToNoteNameLocal(m)
                    if (name.contains("#")) {
                        blackLeftIndexMap[m] = maxOf(0, whiteIdxCounter - 1)
                    } else whiteIdxCounter++
                }

                val alignmentOffsetPx = with(density) { alignmentOffsetDp.toPx() }

                // Precompute integer-midi -> x position (px) using same rules as Piano
                val midiCount = maxMidi - minMidi + 1
                val midiX = FloatArray(midiCount)
                for (m in minMidi..maxMidi) {
                    val idx = m - minMidi
                    val x = if (whiteIndexMap.containsKey(m)) {
                        val widx = whiteIndexMap[m]!!
                        padLeft + (widx + 0.5f) * whiteKeyWidthPx + alignmentOffsetPx
                    } else {
                        val left = blackLeftIndexMap[m] ?: 0
                        val center = (left + 0.5f) * whiteKeyWidthPx
                        val shiftPx = whiteKeyWidthPx * blackKeyShiftFraction
                        padLeft + center + shiftPx + alignmentOffsetPx
                    }
                    midiX[idx] = x
                }

                fun xForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return -10000f
                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)
                    val x0 = midiX[floorM - minMidi]
                    val x1 = midiX[ceilM - minMidi]
                    val frac = midiFloat - floorM
                    return x0 + frac * (x1 - x0)
                }

                // draw vertical semitone columns (so columns line up with piano keys)
                for (m in minMidi..maxMidi) {
                    val x = midiX[m - minMidi]
                    val isNatural = !midiToNoteNameLocal(m).contains("#")
                    if (isNatural) {
                        drawLine(color = Color(0x33FFFFFF), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 1.6f)
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m), x + 6f, padTop + 18f, smallPaint) }
                    } else {
                        drawLine(color = Color(0x22FFFFFF), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 0.9f)
                    }
                }

                if (samples.isEmpty()) {
                    if (showHorizontalGrid) {
                        // draw a subtle horizontal center grid
                        val step = innerH / 6f
                        for (i in 0..6) {
                            val yy = padTop + i * step
                            drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                        }
                    }

                    drawLine(color = Color(0x22FFFFFF), start = Offset(padLeft, padTop + innerH / 2f), end = Offset(padLeft + innerW, padTop + innerH / 2f), strokeWidth = 1f)
                    return@Canvas
                }

                // build path (x = pitch, y = time)
                val path = Path()
                var started = false
                val nowLast = samples.last().tMs
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = padTop + innerH * ((s.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }

                // area fill behind curve
                if (showCurve) {
                    val areaPath = Path().apply { addPath(path) }
                    areaPath.lineTo(padLeft + innerW, padTop + innerH)
                    areaPath.lineTo(padLeft, padTop + innerH)
                    areaPath.close()
                    drawPath(path = areaPath, brush = Brush.verticalGradient(listOf(Color(0x5522B6FF), Color(0x1122B6FF))), style = Fill)

                    // stroke
                    drawPath(path = path, color = Color(0xFF7AD3FF), style = Stroke(width = 3f))
                }

                // points (always draw points so user can hide curve but still see dots)
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = padTop + innerH * ((s.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
                }

                // optionally draw horizontal grid lines (time grid)
                if (showHorizontalGrid) {
                    val rows = 6
                    val step = innerH / rows.toFloat()
                    for (i in 0..rows) {
                        val yy = padTop + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                    }
                }

                // stable markers (vertical lines at the pitch column) — labels controlled by showNoteLabels
                for (m in stableMarkers) {
                    val x = midiX[m.midi - minMidi]
                    val y = padTop + innerH * ((m.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    drawLine(color = Color(0xFFFFD54F), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 2f)
                    if (showNoteLabels && y > padTop + 24f && y < padTop + innerH - 24f) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m.midi), x + 6f, y - 10f, yellowPaint) }
                    }
                }

                // latest sample label (snapped to nearest midi integer) — controlled by showNoteLabels
                val last = samples.last()
                if (!last.midi.isNaN()) {
                    val nearest = last.midi.toInt().coerceIn(minMidi, maxMidi)
                    val x = midiX[nearest - minMidi]
                    val y = padTop + innerH * ((last.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    if (showNoteLabels && y > padTop + 24f && y < padTop + innerH - 24f) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                    }
                }
            }
        }
    }
}
