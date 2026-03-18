package com.jsaiborne.vocalpitchdetector

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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

        consentManager = ConsentManager(this)

        // 1. Gather consent flow
        consentManager.gatherConsent { error ->
            if (error != null) {
                Log.e("UMP", "Consent error: $error")
            }

            // 2. Try to initialize ads if allowed by the user/region
            if (consentManager.canRequestAds()) {
                initializeMobileAdsSdk()
            }
        }

        // 3. Fallback: even if form is skipped, check if we can still request ads
        if (consentManager.canRequestAds()) {
            initializeMobileAdsSdk()
        }

        setContent {
            vocalPitchDetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
