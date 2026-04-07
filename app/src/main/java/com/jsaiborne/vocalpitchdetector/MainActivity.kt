package com.jsaiborne.vocalpitchdetector

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.MobileAds
import com.jsaiborne.vocalpitchdetector.ui.theme.vocalPitchDetectorTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var consentManager: ConsentManager
    private var isMobileAdsInitializeCalled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Enable Edge-to-Edge for Android 15 compatibility
        enableEdgeToEdge()

        consentManager = ConsentManager(this)

        // 2. Gather consent flow
        consentManager.gatherConsent { error ->
            if (error != null) {
                Log.e("UMP", "Consent error: $error")
            }

            // 3. Try to initialize ads if allowed by the user/region
            if (consentManager.canRequestAds()) {
                initializeMobileAdsSdk()
            }
        }

        // 4. Fallback: even if form is skipped, check if we can still request ads
        if (consentManager.canRequestAds()) {
            initializeMobileAdsSdk()
        }

        setContent {
            vocalPitchDetectorTheme {
                // 1. Surface stretches to fill the whole screen (including behind the status bar)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 2. Wrap the NavHost in a Box and apply the padding HERE
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = "main") {

                            composable("main") {
                                // Passing consentManager here so you can show the "Privacy Settings" button
                                MainScreen(navController = navController)
                            }

                            composable("about") {
                                AboutScreen(navController = navController, consentManager = consentManager)
                            }

                            // --- ROUTES FOR RECORDING & PLAYBACK ---

                            composable("recordings") {
                                RecordingsScreen(
                                    onNavigateUp = { navController.navigateUp() },
                                    onSessionSelected = { sessionId ->
                                        // Route to the playback screen, passing the ID
                                        navController.navigate("playback/$sessionId")
                                    }
                                )
                            }

                            composable("playback/{sessionId}") { backStackEntry ->
                                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                                val context = LocalContext.current

                                val recordingsDir = File(context.filesDir, "recordings")
                                val audioFile = File(recordingsDir, "session_${sessionId}_audio.wav")
                                val pitchFile = File(recordingsDir, "session_${sessionId}_pitch.json")

                                val pitchDataList = mutableListOf<RecordedPitchPoint>()
                                val stableMarkersList = mutableListOf<RecordedPitchPoint>()

                                if (pitchFile.exists()) {
                                    try {
                                        val jsonString = pitchFile.readText().trimStart()

                                        if (jsonString.startsWith("[")) {
                                            // --- OLD FORMAT (Legacy support for previous recordings) ---
                                            val jsonArray = JSONArray(jsonString)
                                            for (i in 0 until jsonArray.length()) {
                                                val obj = jsonArray.getJSONObject(i)
                                                pitchDataList.add(
                                                    RecordedPitchPoint(
                                                        timestampMs = obj.getLong("timestampMs"),
                                                        frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                                        midiNote = obj.getInt("midiNote")
                                                    )
                                                )
                                            }
                                        } else if (jsonString.startsWith("{")) {
                                            // --- NEW FORMAT (Includes stable markers) ---
                                            val rootObj = JSONObject(jsonString)

                                            // Extract Pitch Data
                                            if (rootObj.has("pitchData")) {
                                                val pitchArray = rootObj.getJSONArray("pitchData")
                                                for (i in 0 until pitchArray.length()) {
                                                    val obj = pitchArray.getJSONObject(i)
                                                    pitchDataList.add(
                                                        RecordedPitchPoint(
                                                            timestampMs = obj.getLong("timestampMs"),
                                                            frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                                            midiNote = obj.getInt("midiNote")
                                                        )
                                                    )
                                                }
                                            }

                                            // Extract Stable Markers
                                            if (rootObj.has("stableNotes")) {
                                                val stableArray = rootObj.getJSONArray("stableNotes")
                                                for (i in 0 until stableArray.length()) {
                                                    val obj = stableArray.getJSONObject(i)
                                                    stableMarkersList.add(
                                                        RecordedPitchPoint(
                                                            timestampMs = obj.getLong("timestampMs"),
                                                            frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                                            midiNote = obj.getInt("midiNote")
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Playback", "Failed to parse pitch data JSON", e)
                                    }
                                }

                                PlaybackScreen(
                                    audioFile = audioFile,
                                    pitchDataList = pitchDataList,
                                    stableMarkersList = stableMarkersList,
                                    onNavigateUp = { navController.navigateUp() }
                                )
                            }
                        }
                    }
                }
            }
        }
    } // End of onCreate

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) return

        // Initialize the Mobile Ads SDK
        MobileAds.initialize(this) { initializationStatus ->
            // Optional: Log status or load ads here
            Log.d("AdMob", "SDK Initialized: $initializationStatus")
        }
    }
}
