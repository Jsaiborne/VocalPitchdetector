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
    blackKeyShiftFraction: Float = 0.08f,
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

    if (!rotated) {
        // unchanged horizontal implementation (omitted for brevity - same as before)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(whiteKeyHeight)) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val whiteCount = whiteKeys.size
            val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
            val contentWidthPx = whiteCount * whiteKeyWidthPx
            val contentWidthDp = with(density) { contentWidthPx.toDp() }

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
                                        try {
                                            val released = tryAwaitRelease()
                                        } catch (_: Exception) {
                                        }
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
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .padding(bottom = 6.dp),
                                    color = fg,
                                    textAlign = TextAlign.Center
                                )
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
                                    try {
                                        val released = tryAwaitRelease()
                                    } catch (_: Exception) {
                                    }
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
        // ROTATED mode (vertical piano). Render full piano width based on available parent width,
        // then clip to show only the RIGHT portion by shifting the full content left.
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val parentFullWidthDp = maxWidth // use available width as the "full piano width"
            val fullContentWidthDp = parentFullWidthDp
            val visibleWidthDp = fullContentWidthDp * 0.80f // keep right 80% (adjustable)
            val containerHeightPx = with(density) { maxHeight.toPx() }

            val whiteCount = whiteKeys.size
            val keySizePx = with(density) { whiteKeyWidthDp.toPx() } // thickness per key (height)
            val contentHeightPx = whiteCount * keySizePx
            val contentHeightDp = with(density) { contentHeightPx.toDp() }

            // Auto-scroll centers using reversed indices (so low notes are at bottom)
            LaunchedEffect(stableMidi, autoCenter) {
                if (!autoCenter) return@LaunchedEffect
                if (stableMidi == null) return@LaunchedEffect

                val targetPx = run {
                    val idx = whiteKeys.indexOf(stableMidi)
                    if (idx >= 0) {
                        val reversedIndex = whiteCount - 1 - idx
                        val center = (reversedIndex + 0.5f) * keySizePx
                        center - containerHeightPx / 2f
                    } else {
                        val bk = blackKeys.find { it.midi == stableMidi }
                        if (bk != null) {
                            val left = bk.leftWhiteIndex
                            val reversedLeft = whiteCount - 1 - left
                            val center = (reversedLeft + 0.5f) * keySizePx
                            center - containerHeightPx / 2f
                        } else 0f
                    }
                }
                val bounded = targetPx.coerceIn(0f, maxOf(0f, contentHeightPx - containerHeightPx))
                scope.launch { sState.animateScrollTo(bounded.roundToInt()) }
            }

            // Visible container (shows the requested right portion). Vertical scrolling synced to sState.
            Box(
                modifier = Modifier
                    .height(contentHeightDp)
                    .width(visibleWidthDp)
                    .verticalScroll(sState)
            ) {
                // Full-width piano content placed inside and shifted left so right portion is visible.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(fullContentWidthDp)
                        .offset(x = -(fullContentWidthDp - visibleWidthDp)) // shift left so right portion shows
                ) {
                    // Column of white keys in REVERSED order so low notes at bottom
                    Column(modifier = Modifier.fillMaxHeight().width(fullContentWidthDp)) {
                        val reversedWhite = whiteKeys.asReversed()
                        for ((index, midi) in reversedWhite.withIndex()) {
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

                            Box(
                                modifier = Modifier
                                    .height(whiteKeyWidthDp)
                                    .fillMaxWidth()
                                    .shadow(elevation)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .background(bg)
                            ) {
                                if (midiToNoteName(midi).startsWith("C")) {
                                    Text(
                                        text = midiToNoteName(midi),
                                        modifier = Modifier.align(Alignment.CenterStart)
                                            .padding(start = 6.dp),
                                        color = fg
                                    )
                                }
                            }
                        }
                    }

                    // ----------- Black keys: rectangular sizing + hit test -----------
                    // vertical thickness of black key (how much it covers the white key height)
                    val blackThicknessDp = whiteKeyWidthDp * 0.62f
                    val blackThicknessPx = with(density) { blackThicknessDp.toPx() }

                    // horizontal width of black key (a fraction of the full piano width)
                    val blackWidthFraction =
                        0.45f // ← adjust to make black keys wider/narrower visually
                    val blackKeyWidthDp = fullContentWidthDp * blackWidthFraction
                    val blackKeyWidthPx = with(density) { blackKeyWidthDp.toPx() }

                    // center black keys horizontally then shift left a little to reduce overlap
                    val blackOffsetBaseDp = (fullContentWidthDp - blackKeyWidthDp) / 2f
                    val blackOffsetBasePx =
                        (with(density) { fullContentWidthDp.toPx() } - blackKeyWidthPx) / 2f

                    // X shift amount (fraction of full width) to nudge black keys left in landscape
                    val blackShiftFractionX = 0.15f // tweak this (0.0..0.2) to nudge left/right
                    val blackShiftDp = fullContentWidthDp * blackShiftFractionX
                    val blackShiftPx =
                        with(density) { fullContentWidthDp.toPx() * blackShiftFractionX }

                    for (bk in blackKeys) {
                        val midi = bk.midi
                        val leftIndex = bk.leftWhiteIndex
                        val reversedLeft = whiteCount - 1 - leftIndex
                        val centerDp = whiteKeyWidthDp * (reversedLeft.toFloat() + 0.5f)
                        val baseTopDp = centerDp - (blackThicknessDp / 2f)
                        val shiftDp = whiteKeyWidthDp * blackKeyShiftFraction
                        val offsetTopDp = baseTopDp + shiftDp

                        Box(
                            modifier = Modifier
                                .offset(x = blackOffsetBaseDp - blackShiftDp, y = offsetTopDp)
                                .width(blackKeyWidthDp)
                                .height(blackThicknessDp)
                                .background(Color.Black)
                        ) {}
                    }

                    // Touch handling overlay — use same dims as above for hit detection
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        val touchY = offset.y
                                        val touchX = offset.x
                                        val reversedWhite = whiteKeys.asReversed()
                                        val keyIndex = (touchY / keySizePx).roundToInt().coerceIn(0, reversedWhite.size - 1)

                                        var hitMidi: Int? = null

                                        val blackHalfY = blackThicknessPx / 2f
                                        val blackLeftPx = blackOffsetBasePx - blackShiftPx
                                        val blackRightPx = blackLeftPx + blackKeyWidthPx

                                        for (bk in blackKeys) {
                                            val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                                            val centerPx =
                                                (reversedLeft + 0.5f) * keySizePx + (whiteKeyWidthDp.toPx() * blackKeyShiftFraction)
                                            if (kotlin.math.abs(touchY - centerPx) <= blackHalfY) {
                                                if (touchX in blackLeftPx..blackRightPx) {
                                                    hitMidi = bk.midi
                                                    break
                                                }
                                            }
                                        }

                                        if (hitMidi == null) {
                                            hitMidi = reversedWhite[keyIndex]
                                        }
                                        pressedMidi = hitMidi
                                        val freq = 440.0 * 2.0.pow((hitMidi - 69) / 12.0)
                                        ToneGenerator.playToneContinuous(freq)
                                        try {
                                            val released = tryAwaitRelease()
                                        } catch (_: Exception) {
                                        }
                                        ToneGenerator.stop()
                                        pressedMidi = null
                                    },
                                    onTap = { offset ->
                                        val touchY = offset.y
                                        val touchX = offset.x
                                        val reversedWhite = whiteKeys.asReversed()
                                        val keyIndex = (touchY / keySizePx).roundToInt().coerceIn(0, reversedWhite.size - 1)

                                        var hitMidi: Int? = null

                                        val blackHalfY = blackThicknessPx / 2f
                                        val blackLeftPx = blackOffsetBasePx - blackShiftPx
                                        val blackRightPx = blackLeftPx + blackKeyWidthPx

                                        for (bk in blackKeys) {
                                            val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                                            val centerPx =
                                                (reversedLeft + 0.5f) * with(density) { whiteKeyWidthDp.toPx() } + (with(
                                                    density
                                                ) { whiteKeyWidthDp.toPx() } * blackKeyShiftFraction)
                                            if (kotlin.math.abs(touchY - centerPx) <= blackHalfY) {
                                                if (touchX in blackLeftPx..blackRightPx) {
                                                    hitMidi = bk.midi
                                                    break
                                                }
                                            }
                                        }

                                        if (hitMidi == null) hitMidi = reversedWhite[keyIndex]
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
}