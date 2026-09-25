@file:Suppress("MagicNumber", "TooManyFunctions")

package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// --- Detection logic (no Android or Compose types, so it can be unit tested) ----------------------

internal const val HOLD_MS = 600L
internal const val HOLD_WINDOW_SEMITONES = 0.8
internal const val HOLD_MIN_CONFIDENCE = 0.3f
internal const val DROPOUT_GRACE_MS = 300L

/** What [RangeHoldTracker] saw for one pitch update. */
internal data class HoldSnapshot(
    /** Nearest note to the sung pitch, or null when nothing (confident) is being sung. */
    val noteMidi: Int? = null,
    /** True while the pitch is staying put; false when it is sliding around or has just started. */
    val steady: Boolean = false,
    /** 0..1: how much of the hold time the current note has been sung for. */
    val progress: Float = 0f,
    /** Set on the single update where a note has just been held long enough. */
    val heldMidi: Int? = null,
    /** Last time a confident pitch was heard (ms), for "I can't hear you" prompts. */
    val lastVoicedMs: Long = 0L
)

/**
 * Decides when the user has *held* a note. The pitch has to stay within [windowSemitones] of where
 * the note started for [holdMs] (natural wobble and vibrato are fine; a glide is not), and short
 * dropouts up to [dropoutGraceMs] are ignored, so weaker microphones and breathy voices still get
 * through. A glitch or octave jump just starts a new window, so it can never become someone's
 * "lowest" or "highest" note. The reported note is the rounded average pitch of the window.
 */
internal class RangeHoldTracker(
    private val holdMs: Long = HOLD_MS,
    private val windowSemitones: Double = HOLD_WINDOW_SEMITONES,
    private val minConfidence: Float = HOLD_MIN_CONFIDENCE,
    private val dropoutGraceMs: Long = DROPOUT_GRACE_MS
) {
    private var anchorMidi: Double? = null
    private var windowStartMs = 0L
    private var lastHeardMs = 0L
    private var sum = 0.0
    private var count = 0
    private var reported = false
    private var lastVoicedMs = 0L

    fun reset() {
        anchorMidi = null
        sum = 0.0
        count = 0
        reported = false
    }

    fun update(frequencyHz: Float, confidence: Float, nowMs: Long): HoldSnapshot {
        val voiced = frequencyHz > 0f && confidence >= minConfidence
        var noteMidi: Int? = null
        var held: Int? = null

        if (voiced) {
            lastVoicedMs = nowMs
            val exact = freqToMidi(frequencyHz.toDouble())
            noteMidi = exact.roundToInt()

            val anchor = anchorMidi
            if (anchor == null || abs(exact - anchor) > windowSemitones) {
                // Moved too far from where the note started: begin a new window here
                anchorMidi = exact
                windowStartMs = nowMs
                sum = 0.0
                count = 0
                reported = false
            }
            sum += exact
            count++
            lastHeardMs = nowMs

            if (!reported && nowMs - windowStartMs >= holdMs) {
                reported = true
                held = (sum / count).roundToInt()
            }
        } else if (anchorMidi != null && nowMs - lastHeardMs > dropoutGraceMs) {
            reset()
        }

        val progress = if (anchorMidi != null) {
            ((nowMs - windowStartMs).toFloat() / holdMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        return HoldSnapshot(
            noteMidi = noteMidi,
            steady = voiced && count >= 2,
            progress = progress,
            heldMidi = held,
            lastVoicedMs = lastVoicedMs
        )
    }
}

internal data class VoiceType(val label: String, val lowMidi: Int, val highMidi: Int)

/** Typical ranges (MIDI) for the classical voice types, used only as a rough guide. */
internal val VOICE_TYPES = listOf(
    VoiceType("Bass", 40, 64),
    VoiceType("Baritone", 45, 69),
    VoiceType("Tenor", 48, 72),
    VoiceType("Alto", 53, 77),
    VoiceType("Mezzo-soprano", 57, 81),
    VoiceType("Soprano", 60, 84)
)

/** The voice type whose typical range is centred closest to the sung range. */
internal fun estimateVoiceType(lowMidi: Int, highMidi: Int): VoiceType {
    val center = (lowMidi + highMidi) / 2.0
    return VOICE_TYPES.sortedBy { abs((it.lowMidi + it.highMidi) / 2.0 - center) }.first()
}

/** e.g. "1 octave and 3 semitones (15 semitones)". */
internal fun rangeSpanText(lowMidi: Int, highMidi: Int): String {
    val semitones = (highMidi - lowMidi).coerceAtLeast(0)
    val octaves = semitones / 12
    val rest = semitones % 12
    val parts = mutableListOf<String>()
    if (octaves > 0) parts.add(if (octaves == 1) "1 octave" else "$octaves octaves")
    if (rest > 0 || octaves == 0) parts.add(if (rest == 1) "1 semitone" else "$rest semitones")
    return "${parts.joinToString(" and ")} ($semitones semitones)"
}

// --- Screen ---------------------------------------------------------------------------------------

private const val RANGE_PREFS = "AppPreferences"
private const val KEY_RANGE_LOW = "RangeLow"
private const val KEY_RANGE_HIGH = "RangeHigh"
private const val KEY_RANGE_TIME = "RangeTime"
private const val NO_SOUND_AFTER_MS = 3000L
private const val EVENT_SHOWN_MS = 3500L
private const val STUCK_HINT_AFTER_MS = 6000L
private const val COACH_TICK_MS = 250L
private const val PEAK_WINDOW_MS = 3000L
private const val SCALE_LOW_MIDI = 24
private const val SCALE_HIGH_MIDI = 84

private enum class RangeStep { INTRO, WARMUP, LOW, HIGH, RESULT }

/** Why no note is being found, judged from how loud the microphone signal has been. */
private enum class MicHint { BELOW_THRESHOLD, LOUD_ENOUGH }

/** Loudest microphone level seen recently; kept outside Compose state so it doesn't recompose. */
private class PeakLevel {
    var peakDb = -80f
    private var peakAtMs = 0L

    fun record(levelDb: Float, nowMs: Long) {
        if (levelDb >= peakDb || nowMs - peakAtMs > PEAK_WINDOW_MS) {
            peakDb = levelDb
            peakAtMs = nowMs
        }
    }
}

private data class RangeEvent(val text: String, val atMs: Long)

private data class SavedRange(val low: Int, val high: Int, val timeMs: Long)

private fun readSavedRange(context: Context): SavedRange? {
    val prefs = context.getSharedPreferences(RANGE_PREFS, Context.MODE_PRIVATE)
    if (!prefs.contains(KEY_RANGE_LOW) || !prefs.contains(KEY_RANGE_HIGH)) return null
    return SavedRange(
        low = prefs.getInt(KEY_RANGE_LOW, 0),
        high = prefs.getInt(KEY_RANGE_HIGH, 0),
        timeMs = prefs.getLong(KEY_RANGE_TIME, 0L)
    )
}

private fun saveRange(context: Context, low: Int, high: Int) {
    context.getSharedPreferences(RANGE_PREFS, Context.MODE_PRIVATE).edit()
        .putInt(KEY_RANGE_LOW, low)
        .putInt(KEY_RANGE_HIGH, high)
        .putLong(KEY_RANGE_TIME, System.currentTimeMillis())
        .apply()
}

/**
 * Guided vocal range test. Shown instead of the main screen while active; the pitch engine keeps
 * running underneath. Works in portrait and landscape.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun VocalRangeTestScreen(
    engine: PitchEngine,
    isLandscape: Boolean,
    thresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tracker = remember { RangeHoldTracker() }
    val peakLevel = remember { PeakLevel() }
    val volumeRms by engine.volumeRms.collectAsState()
    val volumeDb = rmsToDb(volumeRms)
    val savedRange = remember { readSavedRange(context) }

    var step by remember { mutableStateOf(RangeStep.INTRO) }
    var stepStartMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var snapshot by remember { mutableStateOf(HoldSnapshot()) }
    var frequency by remember { mutableFloatStateOf(-1f) }
    var warmMidi by remember { mutableStateOf<Int?>(null) }
    var lowMidi by remember { mutableStateOf<Int?>(null) }
    var highMidi by remember { mutableStateOf<Int?>(null) }
    var extremeAtMs by remember { mutableLongStateOf(0L) }
    var event by remember { mutableStateOf<RangeEvent?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // A quiet or breathy voice is easy to miss, so ask the engine to be more forgiving while the
    // test is open (restored when it closes)
    DisposableEffect(engine) {
        engine.setSensitiveDetection(true)
        onDispose { engine.setSensitiveDetection(false) }
    }

    LaunchedEffect(engine) {
        engine.volumeRms.collect { rms -> peakLevel.record(rmsToDb(rms), System.currentTimeMillis()) }
    }

    // Time-based coaching (silence, "stuck") needs a clock even when no new pitch arrives
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(COACH_TICK_MS)
        }
    }

    fun goTo(next: RangeStep) {
        step = next
        stepStartMs = System.currentTimeMillis()
        extremeAtMs = stepStartMs
        tracker.reset()
        snapshot = HoldSnapshot(lastVoicedMs = stepStartMs)
    }

    fun onNoteHeld(midi: Int) {
        val t = System.currentTimeMillis()
        when (step) {
            RangeStep.WARMUP -> {
                warmMidi = midi
                lowMidi = midi
                highMidi = midi
                event = RangeEvent("Got it: ${midiToDisplayName(midi)}. Now let's find your lowest note.", t)
                goTo(RangeStep.LOW)
            }
            RangeStep.LOW -> if (midi < (lowMidi ?: midi + 1)) {
                lowMidi = midi
                extremeAtMs = t
                event = RangeEvent(
                    "New lowest: ${midiToDisplayName(midi)}! Keep going, or tap \"That's my lowest\".",
                    t
                )
            }
            RangeStep.HIGH -> if (midi > (highMidi ?: midi - 1)) {
                highMidi = midi
                extremeAtMs = t
                event = RangeEvent(
                    "New highest: ${midiToDisplayName(midi)}! Keep going, or tap \"That's my highest\".",
                    t
                )
            }
            else -> Unit
        }
    }

    LaunchedEffect(engine, step) {
        if (step == RangeStep.INTRO || step == RangeStep.RESULT) return@LaunchedEffect
        engine.state.collect { s ->
            frequency = s.frequency
            val snap = tracker.update(s.frequency, s.confidence, System.currentTimeMillis())
            snapshot = snap
            snap.heldMidi?.let { onNoteHeld(it) }
        }
    }

    val low = lowMidi
    val high = highMidi
    val micHint = if (peakLevel.peakDb < thresholdDb) MicHint.BELOW_THRESHOLD else MicHint.LOUD_ENOUGH
    val silentFor = now - maxOf(snapshot.lastVoicedMs, stepStartMs)
    val micNeedsAttention = silentFor > NO_SOUND_AFTER_MS && micHint == MicHint.BELOW_THRESHOLD &&
        (step == RangeStep.WARMUP || step == RangeStep.LOW || step == RangeStep.HIGH)
    val coach = coachMessage(step, snapshot, now, stepStartMs, extremeAtMs, event, micHint)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RangeHeader(step = step, onExit = onExit)
        Spacer(modifier = Modifier.height(8.dp))

        when (step) {
            RangeStep.INTRO -> RangeIntro(
                savedRange = savedRange,
                thresholdDb = thresholdDb,
                volumeDb = volumeDb,
                onThresholdChange = onThresholdChange,
                onStart = { goTo(RangeStep.WARMUP) },
                onExit = onExit,
                modifier = Modifier.weight(1f)
            )
            RangeStep.WARMUP, RangeStep.LOW, RangeStep.HIGH -> RangeLive(
                step = step,
                isLandscape = isLandscape,
                snapshot = snapshot,
                frequency = frequency,
                coach = coach,
                lowMidi = low,
                highMidi = high,
                thresholdDb = thresholdDb,
                volumeDb = volumeDb,
                micNeedsAttention = micNeedsAttention,
                onThresholdChange = onThresholdChange,
                onConfirmLow = {
                    val t = System.currentTimeMillis()
                    event = RangeEvent("Lowest saved: ${midiToDisplayName(low ?: 0)}. Now let's go up.", t)
                    highMidi = warmMidi
                    goTo(RangeStep.HIGH)
                },
                onConfirmHigh = { goTo(RangeStep.RESULT) },
                onRedo = {
                    if (step == RangeStep.LOW) lowMidi = warmMidi else highMidi = warmMidi
                    goTo(step)
                },
                modifier = Modifier.weight(1f)
            )
            RangeStep.RESULT -> RangeResult(
                lowMidi = low ?: 0,
                highMidi = high ?: 0,
                onRetake = {
                    lowMidi = null
                    highMidi = null
                    warmMidi = null
                    event = null
                    goTo(RangeStep.WARMUP)
                },
                onSave = {
                    saveRange(context, low ?: 0, high ?: 0)
                    onExit()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** What the coach says right now; derived from the current step and what has been heard. */
@Suppress("LongParameterList")
private fun coachMessage(
    step: RangeStep,
    snapshot: HoldSnapshot,
    now: Long,
    stepStartMs: Long,
    extremeAtMs: Long,
    event: RangeEvent?,
    micHint: MicHint
): String {
    val silentFor = now - maxOf(snapshot.lastVoicedMs, stepStartMs)
    val direction = if (step == RangeStep.HIGH) "higher" else "lower"
    return when {
        step == RangeStep.INTRO || step == RangeStep.RESULT -> ""
        event != null && now - event.atMs < EVENT_SHOWN_MS -> event.text
        silentFor > NO_SOUND_AFTER_MS && micHint == MicHint.BELOW_THRESHOLD ->
            "Your voice is below the volume threshold, so I can't pick it up. " +
                "Drag the Microphone level slider below to the left to lower the threshold, " +
                "or sing a little louder."
        silentFor > NO_SOUND_AFTER_MS ->
            "I can hear sound but can't lock onto a clear note. Sing a steady \"ahh\". " +
                "If the room is noisy, raise the volume threshold with the slider below."
        snapshot.noteMidi != null && !snapshot.steady ->
            "Almost! Try to hold the note steady, without sliding."
        step == RangeStep.WARMUP ->
            if (snapshot.noteMidi == null) {
                "First, a warm-up. Sing any comfortable note on \"ahh\" and hold it."
            } else {
                "Hold it… ${midiToDisplayName(snapshot.noteMidi)}"
            }
        now - extremeAtMs > STUCK_HINT_AFTER_MS && snapshot.noteMidi != null ->
            "Can't go $direction? Tap the button below. Never push into strain."
        else ->
            "Go $direction one note at a time, holding each for a second. Stop when it stops feeling comfortable."
    }
}

@Composable
private fun RangeHeader(step: RangeStep, onExit: () -> Unit) {
    val stepLabel = when (step) {
        RangeStep.WARMUP -> "Step 1 of 3 · Warm-up"
        RangeStep.LOW -> "Step 2 of 3 · Lowest note"
        RangeStep.HIGH -> "Step 3 of 3 · Highest note"
        RangeStep.RESULT -> "Your result"
        RangeStep.INTRO -> "Guided test"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Vocal range test", style = MaterialTheme.typography.titleLarge)
            Text(
                stepLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onExit) {
            Icon(Icons.Default.Close, contentDescription = "Exit vocal range test")
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RangeIntro(
    savedRange: SavedRange?,
    thresholdDb: Float,
    volumeDb: Float,
    onThresholdChange: (Float) -> Unit,
    onStart: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Let's find your vocal range: the lowest and highest notes you can sing comfortably. " +
                "I'll guide you step by step.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Before you start", style = MaterialTheme.typography.titleSmall)
                Text("• Find a quiet spot and hold the phone a little way from your mouth.")
                Text("• Sing an open \"ahh\" and hold each note steady for about a second.")
                Text("• Go gently and stop as soon as it feels strained.")
                Text("• Detection covers roughly B1 to D6.")
            }
        }
        if (savedRange != null) {
            val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(savedRange.timeMs))
            Text(
                "Last result: ${midiToDisplayName(savedRange.low)} – ${midiToDisplayName(savedRange.high)} ($date)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MicLevelCard(
            thresholdDb = thresholdDb,
            volumeDb = volumeDb,
            onThresholdChange = onThresholdChange,
            highlight = false,
            hint = "Say \"ahh\": the live level should rise above the threshold."
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start the test") }
        TextButton(onClick = onExit) { Text("Not now") }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun RangeLive(
    step: RangeStep,
    isLandscape: Boolean,
    snapshot: HoldSnapshot,
    frequency: Float,
    coach: String,
    lowMidi: Int?,
    highMidi: Int?,
    thresholdDb: Float,
    volumeDb: Float,
    micNeedsAttention: Boolean,
    onThresholdChange: (Float) -> Unit,
    onConfirmLow: () -> Unit,
    onConfirmHigh: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val readout: @Composable (Modifier) -> Unit = { m ->
        NoteReadout(snapshot = snapshot, frequency = frequency, modifier = m)
    }
    val guidance: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
            ) {
                Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        coach,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (step != RangeStep.WARMUP) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RangeValueCard("Lowest so far", lowMidi, highlighted = step == RangeStep.LOW)
                    RangeValueCard("Highest so far", highMidi, highlighted = step == RangeStep.HIGH)
                }
            }
            when (step) {
                RangeStep.LOW -> {
                    Button(onClick = onConfirmLow, modifier = Modifier.fillMaxWidth()) {
                        Text("That's my lowest")
                    }
                    TextButton(onClick = onRedo) { Text("Redo this step") }
                }
                RangeStep.HIGH -> {
                    Button(onClick = onConfirmHigh, modifier = Modifier.fillMaxWidth()) {
                        Text("That's my highest")
                    }
                    TextButton(onClick = onRedo) { Text("Redo this step") }
                }
                else -> Unit
            }
            MicLevelCard(
                thresholdDb = thresholdDb,
                volumeDb = volumeDb,
                onThresholdChange = onThresholdChange,
                highlight = micNeedsAttention,
                hint = if (micNeedsAttention) {
                    "Drag the threshold left until the live level goes above it while you sing."
                } else {
                    "Your voice should push the live level above the threshold."
                }
            )
        }
    }

    if (isLandscape) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            readout(Modifier.weight(0.45f))
            guidance(
                Modifier
                    .weight(0.55f)
                    .verticalScroll(rememberScrollState())
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            readout(Modifier)
            guidance(Modifier.widthIn(max = 560.dp))
        }
    }
}

/** The volume threshold and live microphone level, so it can be tuned without leaving the test. */
@Composable
private fun MicLevelCard(
    thresholdDb: Float,
    volumeDb: Float,
    onThresholdChange: (Float) -> Unit,
    highlight: Boolean,
    hint: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Microphone level", style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            LiveVolumeSlider(
                thresholdDb = thresholdDb,
                currentVolumeDb = volumeDb,
                onThresholdChange = onThresholdChange
            )
        }
    }
}

@Composable
private fun RangeValueCard(label: String, midi: Int?, highlighted: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = midi?.let { midiToDisplayName(it) } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** The live note name inside a ring that fills as the note is held, with the cents meter below. */
@Composable
private fun NoteReadout(snapshot: HoldSnapshot, frequency: Float, modifier: Modifier = Modifier) {
    val ringColor = if (snapshot.steady) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2f
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke
                )
                if (snapshot.progress > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * snapshot.progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke
                    )
                }
            }
            Text(
                text = snapshot.noteMidi?.let { midiToDisplayName(it) } ?: "--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        CentsMeter(
            frequency = frequency,
            modifier = Modifier.width(240.dp)
        )
    }
}

@Composable
private fun RangeResult(
    lowMidi: Int,
    highMidi: Int,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voiceType = estimateVoiceType(lowMidi, highMidi)
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "${midiToDisplayName(lowMidi)} – ${midiToDisplayName(highMidi)}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            rangeSpanText(lowMidi, highMidi),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        RangeBar(lowMidi = lowMidi, highMidi = highMidi, voiceType = voiceType)
        Text(
            "Closest voice type: ${voiceType.label} " +
                "(typically ${midiToDisplayName(voiceType.lowMidi)} – ${midiToDisplayName(voiceType.highMidi)})",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Voice type depends on more than range, such as tone and where your voice feels most " +
                "comfortable, so treat this as a guide. Notes below B1 or above D6 can't be detected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save & close") }
        OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Retake the test") }
    }
}

/** The sung range on a piano-sized scale, with the closest voice type's typical range underneath. */
@Composable
private fun RangeBar(lowMidi: Int, highMidi: Int, voiceType: VoiceType) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val rangeColor = MaterialTheme.colorScheme.primary
    val typeColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    val trackColor = onSurface.copy(alpha = 0.12f)
    val labelPaint = remember(onSurface) {
        Paint().apply {
            color = onSurface.toArgb()
            textSize = 26f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        val scale = (SCALE_HIGH_MIDI - SCALE_LOW_MIDI).toFloat()
        fun xFor(midi: Int) = ((midi - SCALE_LOW_MIDI) / scale).coerceIn(0f, 1f) * size.width

        val barHeight = 14.dp.toPx()
        val corner = CornerRadius(barHeight / 2f, barHeight / 2f)
        drawRoundRect(color = trackColor, size = Size(size.width, barHeight), cornerRadius = corner)
        drawRoundRect(
            color = rangeColor,
            topLeft = Offset(xFor(lowMidi), 0f),
            size = Size((xFor(highMidi) - xFor(lowMidi)).coerceAtLeast(barHeight), barHeight),
            cornerRadius = corner
        )

        val typeTop = barHeight + 6.dp.toPx()
        val typeHeight = 6.dp.toPx()
        drawRoundRect(
            color = typeColor,
            topLeft = Offset(xFor(voiceType.lowMidi), typeTop),
            size = Size(xFor(voiceType.highMidi) - xFor(voiceType.lowMidi), typeHeight),
            cornerRadius = CornerRadius(typeHeight / 2f, typeHeight / 2f)
        )

        // Labels at every C from C1 to C6
        for (midi in SCALE_LOW_MIDI..SCALE_HIGH_MIDI step 12) {
            drawContext.canvas.nativeCanvas.drawText(
                midiToDisplayName(midi),
                xFor(midi).coerceIn(labelPaint.textSize, size.width - labelPaint.textSize),
                size.height - 4.dp.toPx(),
                labelPaint
            )
        }
    }
}
