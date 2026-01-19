// Piano.kt
package com.example.vocalpitchdetector

import android.annotation.SuppressLint
import android.util.Log
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
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Piano(
    startMidi: Int = 24,
    endMidi: Int = 84,
    onKeyPressed: ((midi: Int, freqHz: Double) -> Unit)? = null,
    activeMidi: Int? = null,
    autoCenter: Boolean = true,
    stableMidi: Int? = null,
    whiteKeyHeight: Dp = 220.dp,
    blackKeyHeight: Dp = 140.dp,
    whiteKeyWidthDp: Dp = 56.dp,
    blackKeyShiftFraction: Float = 0.5f,
    scrollState: ScrollState? = null,
    rotated: Boolean = false,
    // whether to use the SamplePlayer instead of the ToneGenerator
    useSamplePlayer: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val sState = scrollState ?: rememberScrollState()
    val density = LocalDensity.current

    // Helper: safe-play wrapper that falls back to ToneGenerator on exception
    val playNoteSafely: (Int, Double, Boolean) -> Unit = { midi, freq, sustain ->
        try {
            if (useSamplePlayer) {
                SamplePlayer.play(midi)
            } else {
                if (sustain) ToneGenerator.playToneContinuous(freq) else ToneGenerator.playTone(freq, 300)
            }
        } catch (e: Exception) {
            // fallback: use oscillator so user hears something
            Log.w("Piano", "SamplePlayer.play failed for midi=$midi, falling back to ToneGenerator: ${e.message}")
            if (sustain) ToneGenerator.playToneContinuous(freq) else ToneGenerator.playTone(freq, 300)
        }
    }

    // Build white & black lists (low->high)
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
    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    var pressedBlackMidi by remember { mutableStateOf<Int?>(null) }

    if (!rotated) {
        // PORTRAIT
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(whiteKeyHeight)) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val whiteCount = whiteKeys.size
            val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
            val contentWidthPx = whiteCount * whiteKeyWidthPx
            val contentWidthDp = with(density) { contentWidthPx.toDp() }

            LaunchedEffect(stableMidi, autoCenter) {
                if (!autoCenter || stableMidi == null) return@LaunchedEffect
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

                        // per-key flag scoped inside pointerInput so onTap and onPress share it
                        Box(
                            modifier = Modifier
                                .width(whiteKeyWidthDp)
                                .height(whiteKeyHeight)
                                .shadow(elevation)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(bg)
                                .pointerInput(midi) {
                                    var playedByPress = false
                                    detectTapGestures(onPress = {
                                        playedByPress = true
                                        pressedMidi = midi
                                        val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                        // use safe player
                                        playNoteSafely(midi, freq, true)
                                        // notify callback that key was pressed
                                        onKeyPressed?.invoke(midi, freq)
                                        try { tryAwaitRelease() } catch (_: Exception) {}
                                        // on release stop continuous tone (if oscillator)
                                        if (!useSamplePlayer) {
                                            ToneGenerator.stop()
                                        }
                                        pressedMidi = null
                                        playedByPress = false
                                    }, onTap = {
                                        // only handle tap-sound if onPress didn't already play
                                        if (playedByPress) return@detectTapGestures
                                        val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                        playNoteSafely(midi, freq, false)
                                        onKeyPressed?.invoke(midi, freq)
                                    })
                                }
                        ) {
                            if (midiToNoteName(midi).startsWith("C")) {
                                Text(
                                    text = midiToNoteName(midi),
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 6.dp),
                                    color = Color.Black,
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }

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

                    Box(
                        modifier = Modifier
                            .offset(x = offsetDp)
                            .width(blackWidthDp)
                            .height(blackKeyHeight)
                            .shadow(elevation)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .background(bg)
                            .pointerInput(midi) {
                                var playedByPress = false
                                detectTapGestures(onPress = {
                                    playedByPress = true
                                    pressedMidi = midi
                                    val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                    playNoteSafely(midi, freq, true)
                                    onKeyPressed?.invoke(midi, freq)
                                    try { tryAwaitRelease() } catch (_: Exception) {}
                                    if (!useSamplePlayer) {
                                        ToneGenerator.stop()
                                    }
                                    pressedMidi = null
                                    playedByPress = false
                                }, onTap = {
                                    if (playedByPress) return@detectTapGestures
                                    val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                    playNoteSafely(midi, freq, false)
                                    onKeyPressed?.invoke(midi, freq)
                                })
                            }
                    ) {}
                }
            }
        }
    } else {
        // ROTATED MODE
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val parentFullWidthDp = maxWidth
            val visibleWidthDp = parentFullWidthDp * 0.80f
            val containerHeightPx = with(density) { maxHeight.toPx() }

            val whiteCount = whiteKeys.size
            val keySizePx = with(density) { whiteKeyWidthDp.toPx() }
            val contentHeightPx = whiteCount * keySizePx
            val contentHeightDp = with(density) { contentHeightPx.toDp() }

            LaunchedEffect(stableMidi, autoCenter) {
                if (!autoCenter || stableMidi == null) return@LaunchedEffect
                val targetPx = run {
                    val idx = whiteKeys.indexOf(stableMidi)
                    if (idx >= 0) {
                        val reversedIndex = whiteCount - 1 - idx
                        val center = (reversedIndex + 0.5f) * keySizePx
                        center - containerHeightPx / 2f
                    } else {
                        val bk = blackKeys.find { it.midi == stableMidi }
                        if (bk != null) {
                            val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                            val center = (reversedLeft + 0.5f) * keySizePx
                            center - containerHeightPx / 2f
                        } else 0f
                    }
                }
                val bounded = targetPx.coerceIn(0f, maxOf(0f, contentHeightPx - containerHeightPx))
                scope.launch { sState.animateScrollTo(bounded.roundToInt()) }
            }

            val reversedWhite = whiteKeys.asReversed()
            val blackThicknessDp = whiteKeyWidthDp * 0.62f
            val blackThicknessPx = with(density) { blackThicknessDp.toPx() }
            val blackKeyWidthDp = (parentFullWidthDp * 0.40f).coerceAtLeast(48.dp)
            val blackKeyWidthPx = with(density) { blackKeyWidthDp.toPx() }
            val shiftLeftDp = parentFullWidthDp - visibleWidthDp

            // The drawing X-offset for black keys
            val blackDrawLeftDp = (parentFullWidthDp - blackKeyWidthDp) / 2f - (parentFullWidthDp - visibleWidthDp) / 2f
            val blackDrawLeftPx = with(density) { blackDrawLeftDp.toPx() }
            val blackDrawRightPx = blackDrawLeftPx + blackKeyWidthPx

            Box(
                modifier = Modifier
                    .height(contentHeightDp)
                    .width(visibleWidthDp)
                    .verticalScroll(sState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(parentFullWidthDp)
                        .offset(x = -shiftLeftDp)
                ) {
                    // White keys column (rotated)
                    Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                        for ((index, midi) in reversedWhite.withIndex()) {
                            val isActive = activeMidi == midi
                            val isPressed = (pressedMidi == midi) || (pressedIndex == index)
                            val elevation by animateDpAsState(if (isPressed) 12.dp else if (isActive) 6.dp else 2.dp)
                            val scale by animateFloatAsState(if (isPressed) 0.985f else 1f)
                            val bg = when {
                                isPressed -> Color(0xFFBBDEFB)
                                isActive -> Color(0xFF90CAF9)
                                else -> Color.White
                            }
                            Box(
                                modifier = Modifier
                                    .height(whiteKeyWidthDp)
                                    .fillMaxWidth()
                                    .shadow(elevation)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .background(bg)
                            ) {
                                // show C labels at bottom-center of each white key
                                if (midiToNoteName(midi).startsWith("C")) {
                                    Text(
                                        text = midiToNoteName(midi),
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 6.dp),
                                        color = Color.Black,
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }

                    // Black keys overlay (rotated) with matching animation
                    for (bk in blackKeys) {
                        val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                        val centerDp = whiteKeyWidthDp * (reversedLeft.toFloat() + 0.5f)
                        val offsetTopDp = centerDp - (blackThicknessDp / 2f) - (whiteKeyWidthDp * blackKeyShiftFraction)

                        val isPressed = pressedBlackMidi == bk.midi
                        val isActive = activeMidi == bk.midi
                        val elevation by animateDpAsState(if (isPressed) 14.dp else if (isActive) 8.dp else 4.dp)
                        val scale by animateFloatAsState(if (isPressed) 0.99f else 1f)
                        val bg = when {
                            isPressed -> Color(0xFF1565C0)
                            isActive -> Color(0xFF1E88E5)
                            else -> Color.Black
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = blackDrawLeftDp, y = offsetTopDp)
                                .width(blackKeyWidthDp)
                                .height(blackThicknessDp)
                                .shadow(elevation)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(bg)
                        ) {}
                    }

                    // ROTATED TOUCH OVERLAY (fixed coordinate math + single-play guard)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                var playedByPress = false
                                detectTapGestures(
                                    onPress = { offset ->
                                        // offset.x / offset.y are relative to this overlay box
                                        val localY = offset.y
                                        val localX = offset.x

                                        var hitMidi: Int? = null

                                        // compute visible dims in px (same frame as overlay coordinates)
                                        val visibleWidthPx = with(density) { visibleWidthDp.toPx() }
                                        // center black keys horizontally within the visible area
                                        val blackLeftPx = (visibleWidthPx - blackKeyWidthPx) / 2f
                                        val blackRightPx = blackLeftPx + blackKeyWidthPx

                                        // 1) Check black keys by Y-range (X must be inside the central black-key column)
                                        if (localX in blackLeftPx..blackRightPx) {
                                            for (bk in blackKeys) {
                                                val revIdx = whiteCount - 1 - bk.leftWhiteIndex
                                                val bCenterY = (revIdx + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                                val bTop = bCenterY - (blackThicknessPx / 2f)
                                                val bBottom = bCenterY + (blackThicknessPx / 2f)

                                                if (localY in bTop..bBottom) {
                                                    hitMidi = bk.midi
                                                    pressedBlackMidi = bk.midi
                                                    break
                                                }
                                            }
                                        }

                                        // 2) If not a black hit, map Y -> white key
                                        if (hitMidi == null) {
                                            val idx = (localY / keySizePx).toInt().coerceIn(0, whiteCount - 1)
                                            hitMidi = reversedWhite[idx]
                                            pressedIndex = idx
                                        }

                                        playedByPress = true
                                        pressedMidi = hitMidi
                                        val freq = 440.0 * 2.0.pow((hitMidi!! - 69) / 12.0)

                                        // use safe player
                                        playNoteSafely(hitMidi, freq, true)
                                        onKeyPressed?.invoke(hitMidi, freq)

                                        try { tryAwaitRelease() } catch (_: Exception) {}

                                        if (!useSamplePlayer) {
                                            ToneGenerator.stop()
                                        }
                                        pressedMidi = null
                                        pressedIndex = null
                                        pressedBlackMidi = null
                                        playedByPress = false
                                    },
                                    onTap = { offset ->
                                        // skip if onPress already played for this tap
                                        if (playedByPress) return@detectTapGestures

                                        val localY = offset.y
                                        val localX = offset.x

                                        var hitMidi: Int? = null

                                        val visibleWidthPx = with(density) { visibleWidthDp.toPx() }
                                        val blackLeftPx = (visibleWidthPx - blackKeyWidthPx) / 2f
                                        val blackRightPx = blackLeftPx + blackKeyWidthPx

                                        if (localX in blackLeftPx..blackRightPx) {
                                            for (bk in blackKeys) {
                                                val revIdx = whiteCount - 1 - bk.leftWhiteIndex
                                                val bCenterY = (revIdx + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                                if (localY in (bCenterY - blackThicknessPx/2)..(bCenterY + blackThicknessPx/2)) {
                                                    hitMidi = bk.midi
                                                    break
                                                }
                                            }
                                        }
                                        if (hitMidi == null) {
                                            val idx = (localY / keySizePx).toInt().coerceIn(0, whiteCount - 1)
                                            hitMidi = reversedWhite[idx]
                                        }

                                        val freq = 440.0 * 2.0.pow((hitMidi!! - 69) / 12.0)
                                        playNoteSafely(hitMidi, freq, false)
                                        onKeyPressed?.invoke(hitMidi, freq)
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}
