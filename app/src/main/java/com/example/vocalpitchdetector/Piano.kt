package com.example.vocalpitchdetector

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Piano without scrolling (static layout).
 * - Renders white keys across the screen width, overlays black keys approximately.
 * - Press-and-hold plays continuous tone; quick tap plays short tone.
 * - Highlights the active detected MIDI note.
 */
@Composable
fun Piano(
    startMidi: Int = 24, // C1
    endMidi: Int = 84, // C6
    onKeyPressed: ((midi: Int, freqHz: Double) -> Unit)? = null,
    activeMidi: Int? = null,
    whiteKeyHeight: Dp = 220.dp,
    blackKeyHeight: Dp = 140.dp
) {
    val scope = rememberCoroutineScope()

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

// Determine approximate key widths based on screen width (static, non-scrolling)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val whiteCount = whiteKeys.size
    val whiteKeyWidth = if (whiteCount > 0) screenWidthDp / whiteCount else 24.dp

    Box(modifier = Modifier
        .fillMaxWidth()
        .height(whiteKeyHeight)) {

// White keys row
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
            for (midi in whiteKeys) {
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
                    .width(whiteKeyWidth)
                    .height(whiteKeyHeight)
                    .shadow(elevation)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(bg)
                    .pointerInput(midi) {
                        detectTapGestures(onPress = {
                            pressedMidi = midi
                            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
// start continuous tone while pressed
                            ToneGenerator.playToneContinuous(freq)
                            try { val released = tryAwaitRelease() } catch (_: Exception) { }
                            ToneGenerator.stop()
                            pressedMidi = null
                        }, onTap = {
// quick tap: short static tone
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

// Black keys overlay - approximate positioning using whiteKeyWidth and left index
        for (bk in blackKeys) {
            val midi = bk.midi
            val leftIndex = bk.leftWhiteIndex
            val offsetDp = whiteKeyWidth * (leftIndex.toFloat() + 0.68f)
            val blackWidthDp = whiteKeyWidth * 0.62f
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

private fun computeBlackKeyOffsetFraction(midi: Int): Float {
    val posInOctave = midi % 12
    return when (posInOctave) {
        1 -> 0.7f
        3 -> 1.7f
        6 -> 3.7f
        8 -> 4.7f
        10 -> 5.7f
        else -> 0.7f
    }
}