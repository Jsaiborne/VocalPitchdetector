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
    // pressedIndex is used in rotated mode to point to reversedWhite index (0 = top/high)
    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    // pressedBlackMidi is used in rotated mode for black key highlight
    var pressedBlackMidi by remember { mutableStateOf<Int?>(null) }

    if (!rotated) {
        // Horizontal (portrait) behavior — unchanged logic (per-key pointerInput)
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
                                            tryAwaitRelease()
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
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
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
                                        tryAwaitRelease()
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
        // ROTATED mode (vertical piano). Rewritten touch mapping with robust geometry.
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val parentFullWidthDp = maxWidth // use available width as the "full piano width"
            val fullContentWidthDp = parentFullWidthDp
            val visibleWidthDp = fullContentWidthDp * 0.80f // portion shown
            val containerHeightPx = with(density) { maxHeight.toPx() }

            val whiteCount = whiteKeys.size
            val keySizePx = with(density) { whiteKeyWidthDp.toPx() } // thickness per key (height)
            val contentHeightPx = whiteCount * keySizePx
            val contentHeightDp = with(density) { contentHeightPx.toDp() }

            // Auto-scroll centers using reversed indices (so low notes at bottom)
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

            // UI indices use reversed order: reversedWhite[0] is top-most/highest note
            val reversedWhite = whiteKeys.asReversed()

            // geometry for black keys (rectangular bars on the left area of visible piano)
            val blackThicknessDp = whiteKeyWidthDp * 0.62f
            val blackThicknessPx = with(density) { blackThicknessDp.toPx() }

            val blackKeyWidthDp = (fullContentWidthDp * 0.40f).coerceAtLeast(48.dp)
            val blackKeyWidthPx = with(density) { blackKeyWidthDp.toPx() }

            val shiftLeftDp = fullContentWidthDp - visibleWidthDp
            val shiftLeftPx = with(density) { shiftLeftDp.toPx() }

            // base left for black keys inside the full content (we will subtract shiftLeftPx when comparing touchX)
            val blackOffsetBaseDp = (fullContentWidthDp - blackKeyWidthDp) / 2f
            val blackOffsetBasePx = with(density) { blackOffsetBaseDp.toPx() }

            // a small dead-zone radius (as fraction of key height) to avoid accidental hits near borders
            val deadZoneFraction = 0.15f

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
                        .offset(x = -shiftLeftDp) // shift left so right portion shows
                ) {
                    // Column of white keys in REVERSED order so low notes at bottom
                    Column(modifier = Modifier.fillMaxHeight().width(fullContentWidthDp)) {
                        for ((index, midi) in reversedWhite.withIndex()) {
                            val isActive = activeMidi == midi
                            val isPressed = pressedIndex == index
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

                    // Black keys overlay (drawn as bars)
                    for (bk in blackKeys) {
                        val midi = bk.midi
                        val leftIndex = bk.leftWhiteIndex
                        val reversedLeft = whiteCount - 1 - leftIndex
                        val centerDp = whiteKeyWidthDp * (reversedLeft.toFloat() + 0.5f)
                        val baseTopDp = centerDp - (blackThicknessDp / 2f)
                        val shiftDp = whiteKeyWidthDp * blackKeyShiftFraction
                        val offsetTopDp = baseTopDp - shiftDp // shift a little up

                        val isActive = activeMidi == midi
                        val isPressed = pressedBlackMidi == midi
                        val elevation by animateDpAsState(if (isPressed) 14.dp else if (isActive) 8.dp else 4.dp)
                        val scale by animateFloatAsState(if (isPressed) 0.99f else 1f)
                        val bg = when {
                            isPressed -> Color(0xFF1565C0)
                            isActive -> Color(0xFF1E88E5)
                            else -> Color.Black
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = blackOffsetBaseDp - (fullContentWidthDp - visibleWidthDp) / 2f, y = offsetTopDp)
                                .width(blackKeyWidthDp)
                                .height(blackThicknessDp)
                                .shadow(elevation)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(bg)
                        ) {}
                    }

                    // Touch handling overlay — use same dims as above for hit detection and drive pressedIndex
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        // offset is local to this box (which is already shifted to show right portion)
                                        // We must include vertical scroll value (sState.value) because verticalScroll shifts content.
                                        val localY = offset.y + sState.value.toFloat()
                                        val localX = offset.x

                                        // compute nearest reversedWhite index by pure geometry (center at (i+0.5)*keySizePx)
                                        fun nearestReversedIndex(yPx: Float): Int {
                                            // raw index in reversed order (0..whiteCount-1)
                                            val raw = ((yPx / keySizePx) - 0.5f).roundToInt()
                                            return raw.coerceIn(0, whiteCount - 1)
                                        }

                                        // black-key hit detection (priority)
                                        var hitMidi: Int? = null
                                        var hitIsBlack = false

                                        val blackHalfY = blackThicknessPx / 2f
                                        // compute black X-range relative to visible area:
                                        // black boxes were drawn at x = blackOffsetBaseDp - (fullContentWidthDp - visibleWidthDp)/2f
                                        val blackDrawLeftPx = with(density) { (blackOffsetBaseDp - (fullContentWidthDp - visibleWidthDp) / 2f).toPx() }
                                        val blackDrawRightPx = blackDrawLeftPx + blackKeyWidthPx

                                        // tighten vertical tolerance a bit to reduce accidental hits
                                        val verticalTolerance = blackHalfY * (1f - deadZoneFraction)

                                        for (bk in blackKeys) {
                                            val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                                            val centerPx = (reversedLeft + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                            if (abs(localY - centerPx) <= verticalTolerance) {
                                                if (localX in blackDrawLeftPx..blackDrawRightPx) {
                                                    hitMidi = bk.midi
                                                    hitIsBlack = true
                                                    break
                                                }
                                            }
                                        }

                                        if (!hitIsBlack) {
                                            // nearest white (reversed index)
                                            val idx = nearestReversedIndex(localY)

                                            // optional dead-zone: if touch is near the border between two keys, require closeness to center
                                            val centerPx = (idx + 0.5f) * keySizePx
                                            val maxAllowed = keySizePx * (0.5f - deadZoneFraction)
                                            val dist = abs(localY - centerPx)
                                            if (dist <= maxAllowed) {
                                                hitMidi = reversedWhite[idx]
                                                // store pressed index (for UI)
                                                pressedIndex = idx
                                                pressedBlackMidi = null
                                            } else {
                                                // Touch was too close to border — treat as no-hit to avoid accidental wrong key
                                                hitMidi = null
                                                pressedIndex = null
                                                pressedBlackMidi = null
                                            }
                                        } else {
                                            // black key selected: set highlight accordingly
                                            pressedBlackMidi = hitMidi
                                            pressedIndex = null
                                        }

                                        if (hitMidi != null) {
                                            // play continuous tone
                                            val freq = 440.0 * 2.0.pow((hitMidi - 69) / 12.0)
                                            // sync midi -> pressedMidi so UI driven by same source
                                            pressedMidi = hitMidi
                                            ToneGenerator.playToneContinuous(freq)
                                        } else {
                                            // no valid hit: do nothing
                                        }

                                        try {
                                            tryAwaitRelease()
                                        } catch (_: Exception) {
                                        }

                                        // stop tone and clear highlights
                                        ToneGenerator.stop()
                                        pressedMidi = null
                                        pressedIndex = null
                                        pressedBlackMidi = null
                                    },

                                    onTap = { offset ->
                                        val localY = offset.y + sState.value.toFloat()
                                        val localX = offset.x

                                        fun nearestReversedIndex(yPx: Float): Int {
                                            val raw = ((yPx / keySizePx) - 0.5f).roundToInt()
                                            return raw.coerceIn(0, whiteCount - 1)
                                        }

                                        var hitMidi: Int? = null
                                        var hitIsBlack = false

                                        val blackHalfY = blackThicknessPx / 2f
                                        val blackDrawLeftPx = with(density) { (blackOffsetBaseDp - (fullContentWidthDp - visibleWidthDp) / 2f).toPx() }
                                        val blackDrawRightPx = blackDrawLeftPx + blackKeyWidthPx
                                        val verticalTolerance = blackHalfY * (1f - deadZoneFraction)

                                        for (bk in blackKeys) {
                                            val reversedLeft = whiteCount - 1 - bk.leftWhiteIndex
                                            val centerPx = (reversedLeft + 0.5f) * keySizePx - (keySizePx * blackKeyShiftFraction)
                                            if (abs(localY - centerPx) <= verticalTolerance) {
                                                if (localX in blackDrawLeftPx..blackDrawRightPx) {
                                                    hitMidi = bk.midi
                                                    hitIsBlack = true
                                                    break
                                                }
                                            }
                                        }

                                        if (!hitIsBlack) {
                                            val idx = nearestReversedIndex(localY)
                                            val centerPx = (idx + 0.5f) * keySizePx
                                            val maxAllowed = keySizePx * (0.5f - deadZoneFraction)
                                            val dist = abs(localY - centerPx)
                                            if (dist <= maxAllowed) {
                                                hitMidi = reversedWhite[idx]
                                                pressedIndex = idx
                                                pressedBlackMidi = null
                                            } else {
                                                hitMidi = null
                                            }
                                        } else {
                                            pressedBlackMidi = hitMidi
                                            pressedIndex = null
                                        }

                                        if (hitMidi != null) {
                                            pressedMidi = hitMidi
                                            val freq = 440.0 * 2.0.pow((hitMidi - 69) / 12.0)
                                            ToneGenerator.playTone(freq, 300)

                                            // keep highlight briefly
                                            scope.launch {
                                                kotlinx.coroutines.delay(260)
                                                if (pressedMidi == hitMidi) pressedMidi = null
                                                if (pressedIndex == (whiteKeys.indexOf(hitMidi).let { if (it >= 0) whiteCount - 1 - it else null })) {
                                                    // clear pressedIndex if still same
                                                    pressedIndex = null
                                                }
                                                if (pressedBlackMidi == hitMidi) pressedBlackMidi = null
                                            }

                                            onKeyPressed?.invoke(hitMidi, freq)
                                        }
                                    }
                                )
                            }
                    ) {}
                }
            }
        }
    }
}
