package com.example.vocalpitchdetector

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.ScrollState

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Piano(
    startMidi: Int = 24, // C1
    endMidi: Int = 84,   // C6
    scrollState: ScrollState? = null, // optional external shared scroll state
    onKeyPressed: ((midi: Int, freqHz: Double) -> Unit)? = null,
    activeMidi: Int? = null,
    autoCenter: Boolean = true,
    stableMidi: Int? = null,
    whiteKeyHeight: Dp = 220.dp,
    blackKeyHeight: Dp = 140.dp,
    whiteKeyWidthDp: Dp = 56.dp,
    blackKeyShiftFraction: Float = 0.5f // positive shifts black keys to the right
) {
    val scope = rememberCoroutineScope()
    val internalScroll = rememberScrollState()
    val sState = scrollState ?: internalScroll

    val density = LocalDensity.current

    // Build white and black key lists
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

    BoxWithConstraints(modifier = Modifier
        .fillMaxWidth()
        .height(whiteKeyHeight)) {

        val containerWidthPx = with(density) { maxWidth.toPx() }
        val whiteCount = whiteKeys.size
        val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
        val contentWidthPx = whiteCount * whiteKeyWidthPx
        val contentWidthDp = with(density) { contentWidthPx.toDp() }

        // Auto-scroll when a stableMidi is emitted (only if autoCenter true)
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
            scope.launch { sState.animateScrollTo(bounded.toInt()) }
        }

        // Scrollable content box: white keys + black keys overlay inside the same container so they move together
        Box(modifier = Modifier
            .width(contentWidthDp)
            .horizontalScroll(sState)) {

            // White keys row
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
                                scope.launch { ToneGenerator.playTone(freq, 300) }
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
                            scope.launch { ToneGenerator.playTone(freq, 300) }
                            onKeyPressed?.invoke(midi, freq)
                        })
                    }
                ) {}
            }
        }
    }
}
