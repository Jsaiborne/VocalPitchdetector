package com.example.vocalpitchdetector

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.vocalpitchdetector.ui.theme.VocalPitchdetectorTheme

class MainActivity : ComponentActivity() {
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // No-op here; MainScreen / Compose will handle state and explain if permission is denied
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request mic permission once on startup (you can move this into Compose for a nicer UX)
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            // Use your app theme (Material3) and a Surface colored by the theme's background
            VocalPitchdetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
