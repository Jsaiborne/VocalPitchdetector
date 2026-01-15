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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

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
    rotated: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val sState = scrollState ?: rememberScrollState()
    val density = LocalDensity.current

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
        // --- PORTRAIT CODE: UNTOUCHED AS REQUESTED ---
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
                        Box(
                            modifier = Modifier
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
                                        try { tryAwaitRelease() } catch (_: Exception) {}
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
                                detectTapGestures(onPress = {
                                    pressedMidi = midi
                                    val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                                    ToneGenerator.playToneContinuous(freq)
                                    try { tryAwaitRelease() } catch (_: Exception) {}
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
        // --- ROTATED MODE: FIXED TOUCH LOGIC + MATCH PORTRAIT ANIMATION & LABELS ---
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
                                // Match portrait: show C labels at bottom-center of each white key
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

                    // --- REWRITTEN TOUCH OVERLAY (unchanged logic) ---
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        // FIXED: offset is already relative to this Box's content.
                                        // Do NOT add sState.value.
                                        val localY = offset.y
                                        val localX = offset.x

                                        var hitMidi: Int? = null

                                        // 1. Check Black Keys First
                                        for (bk in blackKeys) {
                                            val revIdx = whiteCount - 1 - bk.leftWhiteIndex
                                            val bCenterY = (revIdx + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                            val bTop = bCenterY - (blackThicknessPx / 2f)
                                            val bBottom = bCenterY + (blackThicknessPx / 2f)

                                            if (localY in bTop..bBottom && localX in blackDrawLeftPx..blackDrawRightPx) {
                                                hitMidi = bk.midi
                                                pressedBlackMidi = bk.midi
                                                break
                                            }
                                        }

                                        // 2. Check White Keys
                                        if (hitMidi == null) {
                                            val idx = (localY / keySizePx).toInt().coerceIn(0, whiteCount - 1)
                                            hitMidi = reversedWhite[idx]
                                            pressedIndex = idx
                                        }

                                        pressedMidi = hitMidi
                                        val freq = 440.0 * 2.0.pow((hitMidi!! - 69) / 12.0)
                                        ToneGenerator.playToneContinuous(freq)

                                        try { tryAwaitRelease() } catch (_: Exception) {}

                                        ToneGenerator.stop()
                                        pressedMidi = null
                                        pressedIndex = null
                                        pressedBlackMidi = null
                                    },
                                    onTap = { offset ->
                                        val localY = offset.y
                                        val localX = offset.x
                                        var hitMidi: Int? = null

                                        for (bk in blackKeys) {
                                            val revIdx = whiteCount - 1 - bk.leftWhiteIndex
                                            val bCenterY = (revIdx + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                            if (localY in (bCenterY - blackThicknessPx/2)..(bCenterY + blackThicknessPx/2) &&
                                                localX in blackDrawLeftPx..blackDrawRightPx) {
                                                hitMidi = bk.midi
                                                break
                                            }
                                        }
                                        if (hitMidi == null) {
                                            val idx = (localY / keySizePx).toInt().coerceIn(0, whiteCount - 1)
                                            hitMidi = reversedWhite[idx]
                                        }

                                        val freq = 440.0 * 2.0.pow((hitMidi!! - 69) / 12.0)
                                        ToneGenerator.playTone(freq, 300)
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
