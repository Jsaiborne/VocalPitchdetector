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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.MobileAds
import com.jsaiborne.vocalpitchdetector.ui.theme.vocalPitchDetectorTheme
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
