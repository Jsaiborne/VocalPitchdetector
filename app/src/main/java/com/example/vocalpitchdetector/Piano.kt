package com.example.vocalpitchdetector

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Piano that supports normal (horizontal) and rotated (vertical) modes.
 *
 * rotated = false -> original behavior: horizontal keys, horizontal scroll
 * rotated = true  -> keys stacked vertically; vertical scroll. Touch input remapped to keys (including black key hit detection).
 *
 * Accepts optional ScrollState so piano and graph can share the same scroll in either axis.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Piano(
    startMidi: Int = 24, // C1
    endMidi: Int = 84,   // C6
    onKeyPressed: ((midi: Int, freqHz: Double) -> Unit)? = null,
    activeMidi: Int? = null,
    autoCenter: Boolean = true,
    stableMidi: Int? = null,
    whiteKeyHeight: Dp = 220.dp,
    blackKeyHeight: Dp = 140.dp,
    whiteKeyWidthDp: Dp = 56.dp,
    blackKeyShiftFraction: Float = 0.08f, // positive shifts black keys to the right (or downward in rotated mode)
    scrollState: ScrollState? = null,
    rotated: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val sState = scrollState ?: rememberScrollState()
    val density = LocalDensity.current

    // Build white & black lists
    val whiteKeys = remember(startMidi, endMidi) {
        mutableListOf<Int>().apply {
            for (m in startMidi..endMidi) {
                val name = midiToNoteName(m)
                if (!name.contains("#")) add(m)
            }
        }
    }

    data class BlackKey(val midi: Int, val leftWhiteIndex: Int)
    val blackKeys = remember(startMidi, endMidi) {
        val list = mutableListOf<BlackKey>()
        var whiteIndex = 0
        for (m in startMidi..endMidi) {
            val name = midiToNoteName(m)
            if (name.contains("#")) {
                val left = maxOf(0, whiteIndex - 1)
                list.add(BlackKey(m, left))
            } else {
                whiteIndex++
            }
        }
        list
    }

    var pressedMidi by remember { mutableStateOf<Int?>(null) }

    if (!rotated) {
        // Original horizontal piano path (unchanged behavior)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(whiteKeyHeight)) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val whiteCount = whiteKeys.size
            val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
            val contentWidthPx = whiteCount * whiteKeyWidthPx
            val contentWidthDp = with(density) { contentWidthPx.toDp() }

            // Auto-scroll on stable note
            LaunchedEffect(stableMidi, autoCenter) {
                if (!autoCenter) return@LaunchedEffect
                if (stableMidi == null) return@LaunchedEffect
                val targetPx = run {
                    val whiteIndex = whiteKeys.indexOf(stableMidi)
                    if (whiteIndex >= 0) {
                        val center = (whiteIndex + 0.5f) * whiteKeyWidthPx
                        center - containerWidthPx / 2f
                    } else {
                        val bk = blackKeys.find { it.midi == stableMidi }
                        if (bk != null) {
                            val center = (bk.leftWhiteIndex + 0.5f) * whiteKeyWidthPx
                            center - containerWidthPx / 2f
                        } else 0f
                    }
                }
                val bounded = targetPx.coerceIn(0f, maxOf(0f, contentWidthPx - containerWidthPx))
                scope.launch { sState.animateScrollTo(bounded.roundToInt()) }
            }

            Box(modifier = Modifier.width(contentWidthDp).horizontalScroll(sState)) {
                // White keys
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                    for ((index, midi) in whiteKeys.withIndex()) {
                        val isActive = activeMidi == midi
                        val isPressed = pressedMidi == midi
                        val elevation by animateDpAsState(if (isPressed) 12.dp else if (isActive) 6.dp else 2.dp)
                        val scale by animateFloatAsState(if (isPressed) 0.985f else 1f)
                        val bg = when {
                            isPressed -> Color(0xFFBBDEFB)
                            isActive -> Color(0xFF90CAF9)
                            else -> Color.White
                        }
                        val fg = Color.Black

                        Box(modifier = Modifier
                            .width(whiteKeyWidthDp)
                            .height(whiteKeyHeight)
                            .shadow(elevation)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .background(bg)
                            .pointerInput(midi) {
                                detectTapGestures(onPress = {
                                    pressedMidi = midi
                                    val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                    ToneGenerator.playToneContinuous(freq)
                                    try { val released = tryAwaitRelease() } catch (_: Exception) { }
                                    ToneGenerator.stop()
                                    pressedMidi = null
                                }, onTap = {
                                    val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                    ToneGenerator.playTone(freq, 300)
                                    onKeyPressed?.invoke(midi, freq)
                                })
                            }
                        ) {
                            if (midiToNoteName(midi).startsWith("C")) {
                                Text(text = midiToNoteName(midi), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), color = fg, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                // Black keys overlay
                val blackWidthDp = whiteKeyWidthDp * 0.62f
                for (bk in blackKeys) {
                    val midi = bk.midi
                    val leftIndex = bk.leftWhiteIndex
                    val centerDp = whiteKeyWidthDp * (leftIndex.toFloat() + 0.5f)
                    val baseLeftDp = centerDp - (blackWidthDp / 2f)
                    val shiftDp = whiteKeyWidthDp * blackKeyShiftFraction
                    val offsetDp = baseLeftDp + shiftDp

                    val isActive = activeMidi == midi
                    val isPressed = pressedMidi == midi
                    val elevation by animateDpAsState(if (isPressed) 14.dp else if (isActive) 8.dp else 4.dp)
                    val scale by animateFloatAsState(if (isPressed) 0.99f else 1f)
                    val bg = when {
                        isPressed -> Color(0xFF1565C0)
                        isActive -> Color(0xFF1E88E5)
                        else -> Color.Black
                    }

                    Box(modifier = Modifier
                        .offset(x = offsetDp)
                        .width(blackWidthDp)
                        .height(blackKeyHeight)
                        .shadow(elevation)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(bg)
                        .pointerInput(midi) {
                            detectTapGestures(onPress = {
                                pressedMidi = midi
                                val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                ToneGenerator.playToneContinuous(freq)
                                try { val released = tryAwaitRelease() } catch (_: Exception) { }
                                ToneGenerator.stop()
                                pressedMidi = null
                            }, onTap = {
                                val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                ToneGenerator.playTone(freq, 300)
                                onKeyPressed?.invoke(midi, freq)
                            })
                        }
                    ) {}
                }
            }
        }
    } else {
        // ROTATED mode: vertical stack of white keys (one per row). Use verticalScroll(sState).
        // Keys thickness = whiteKeyWidthDp (interpreted as keySizeDp)
        BoxWithConstraints(modifier = Modifier.fillMaxHeight().width(280.dp)) {
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val whiteCount = whiteKeys.size
            val keySizePx = with(density) { whiteKeyWidthDp.toPx() } // thickness per key (height)
            val contentHeightPx = whiteCount * keySizePx
            val contentHeightDp = with(density) { contentHeightPx.toDp() }

            // Auto-scroll on stable note -> center the stable pitch in the visible height
            LaunchedEffect(stableMidi, autoCenter) {
                if (!autoCenter) return@LaunchedEffect
                if (stableMidi == null) return@LaunchedEffect

                val targetPx = run {
                    val whiteIndex = whiteKeys.indexOf(stableMidi)
                    if (whiteIndex >= 0) {
                        val center = (whiteIndex + 0.5f) * keySizePx
                        center - containerHeightPx / 2f
                    } else {
                        val bk = blackKeys.find { it.midi == stableMidi }
                        if (bk != null) {
                            val center = (bk.leftWhiteIndex + 0.5f) * keySizePx
                            center - containerHeightPx / 2f
                        } else 0f
                    }
                }
                val bounded = targetPx.coerceIn(0f, maxOf(0f, contentHeightPx - containerHeightPx))
                scope.launch { sState.animateScrollTo(bounded.roundToInt()) }
            }

            // Use verticalScroll so scrollState is shared vertically with the graph in rotated mode
            Box(modifier = Modifier.height(contentHeightDp).verticalScroll(sState)) {
                // Draw white keys vertically (Column)
                Column(modifier = Modifier.fillMaxHeight().width(280.dp)) {
                    for ((index, midi) in whiteKeys.withIndex()) {
                        val isActive = activeMidi == midi
                        val isPressed = pressedMidi == midi
                        val elevation by animateDpAsState(if (isPressed) 12.dp else if (isActive) 6.dp else 2.dp)
                        val scale by animateFloatAsState(if (isPressed) 0.99f else 1f)
                        val bg = when {
                            isPressed -> Color(0xFFBBDEFB)
                            isActive -> Color(0xFF90CAF9)
                            else -> Color.White
                        }
                        val fg = Color.Black

                        Box(modifier = Modifier
                            .height(whiteKeyWidthDp)
                            .fillMaxWidth()
                            .shadow(elevation)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .background(bg)
                        ) {
                            // For rotated mode we don't set per-key pointerInput; instead use overlay pointer below to compute hits
                            if (midiToNoteName(midi).startsWith("C")) {
                                Text(text = midiToNoteName(midi), modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp), color = fg)
                            }
                        }
                    }
                }

                // Black keys overlay: we will draw them by placing boxes at calculated offsets (they are narrower horizontally)
                val blackWidthDp = whiteKeyWidthDp * 0.62f
                for (bk in blackKeys) {
                    val midi = bk.midi
                    val leftIndex = bk.leftWhiteIndex
                    val centerDp = whiteKeyWidthDp * (leftIndex.toFloat() + 0.5f)
                    val baseTopDp = centerDp - (blackWidthDp / 2f)
                    val shiftDp = whiteKeyWidthDp * blackKeyShiftFraction
                    val offsetTopDp = baseTopDp + shiftDp

                    Box(modifier = Modifier
                        .offset(y = offsetTopDp)
                        .height(blackWidthDp)
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp) // visually narrow the black key
                        .background(Color.Black)
                    ) {}
                }

                // Overlay pointer detector to handle taps/drags mapped to rotated layout (vertical axis)
                Box(modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                // offset: position in the rotated piano box, where y is vertical coordinate
                                val touchY = offset.y
                                val touchX = offset.x
                                // compute white index
                                val keyIndex = (touchY / keySizePx).toInt().coerceIn(0, whiteKeys.size - 1)
                                // check black-key hit: iterate blackKeys nearby and test proximity
                                var hitMidi: Int? = null
                                val blackHalfPx = with(density) { (whiteKeyWidthDp * 0.62f).toPx() / 2f }
                                val blackLeft = (0.12f * with(density) { 280.dp.toPx() }) // padding range to limit black hits horizontally
                                val blackRight = with(density) { 280.dp.toPx() } - blackLeft
                                // find any black key whose center is near touchY
                                for (bk in blackKeys) {
                                    val center = (bk.leftWhiteIndex + 0.5f) * keySizePx + (whiteKeyWidthDp.toPx() * blackKeyShiftFraction)
                                    if (abs(touchY - center) <= blackHalfPx) {
                                        // also check x in black horizontal bounds
                                        if (touchX in blackLeft..blackRight) {
                                            hitMidi = bk.midi
                                            break
                                        }
                                    }
                                }
                                if (hitMidi == null) {
                                    hitMidi = whiteKeys[keyIndex]
                                }
                                pressedMidi = hitMidi
                                val freq = 440.0 * 2.0.pow((hitMidi - 69) / 12.0)
                                ToneGenerator.playToneContinuous(freq)
                                try { val released = tryAwaitRelease() } catch (_: Exception) { }
                                ToneGenerator.stop()
                                pressedMidi = null
                            },
                            onTap = { offset ->
                                // quick tap -> play short tone (same mapping)
                                val touchY = offset.y
                                val keyIndex = (touchY / with(density) { whiteKeyWidthDp.toPx() }).toInt().coerceIn(0, whiteKeys.size - 1)
                                var hitMidi: Int? = null
                                val blackHalfPx = with(density) { (whiteKeyWidthDp * 0.62f).toPx() / 2f }
                                val blackLeft = (0.12f * with(density) { 280.dp.toPx() })
                                val blackRight = with(density) { 280.dp.toPx() } - blackLeft
                                for (bk in blackKeys) {
                                    val center = (bk.leftWhiteIndex + 0.5f) * with(density) { whiteKeyWidthDp.toPx() } + (with(density) { whiteKeyWidthDp.toPx() } * blackKeyShiftFraction)
                                    if (abs(touchY - center) <= blackHalfPx) {
                                        if (offset.x in blackLeft..blackRight) {
                                            hitMidi = bk.midi
                                            break
                                        }
                                    }
                                }
                                if (hitMidi == null) hitMidi = whiteKeys[keyIndex]
                                val freq = 440.0 * 2.0.pow((hitMidi - 69) / 12.0)
                                ToneGenerator.playTone(freq, 300)
                                onKeyPressed?.invoke(hitMidi, freq)
                            }
                        )
                    }
                ) {}
            }
        }
    }
}
