@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.vocalpitchdetector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

/**
 * AboutScreen split into its own file. Call with AboutScreen(navController).
 * Replace the placeholder contact constants with your real info.
 */
@Composable
fun AboutScreen(navController: NavHostController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // TODO: replace these with your real contact details
    val EMAIL = "you@example.com"
    val WEBSITE = "https://example.com"
    val GITHUB = "https://github.com/yourusername"

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("App", "Dev")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("About") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }) {
                    Text(title, modifier = Modifier.padding(16.dp))
                }
            }
        }

        when (selectedTab) {
            0 -> {
                val version = try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    pInfo.versionName ?: "?"
                } catch (e: Exception) {
                    "?"
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(text = "Vocal Pitch Monitor", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Version: $version", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This app displays live vocal pitch information, confidence, and a scrollable pitch trace. It uses on-device audio analysis and a few visualization tweaks."
                    )
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(text = "Developer", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Your Name Here", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = "Contact", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Email: $EMAIL",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("mailto:$EMAIL") }
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Website: $WEBSITE",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(WEBSITE) }
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "GitHub: $GITHUB",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(GITHUB) }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}
