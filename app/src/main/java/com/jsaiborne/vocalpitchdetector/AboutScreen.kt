@file:OptIn(ExperimentalMaterial3Api::class)

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * AboutScreen split into its own file. Call with AboutScreen(navController).
 * Replace the placeholder contact constants with your real info.
 */
@Composable
fun AboutScreen(navController: NavHostController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // TODO: replace these with your real contact details
    val EMAIL = "bahehdowski@gmail.com"
    val WEBSITE = "https://jsaiborne-portfolio.netlify.app/"
    val GITHUB = "https://github.com/Jsaiborne"

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
                        text = """
This app shows your singing voice in real time — the musical pitch, a confidence score, and a scrolling pitch trace so you can see how steady you are. Tap the piano to play notes or compare your pitch.

Piano sound samples by jobro -- https://freesound.org/ -- License: Attribution 3.0
                        """.trimIndent()
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
                    Text(
                        text = """
Hey! I’m Jotham Saiborne — a Master’s student in Computer Science, web and Android developer, and hobbyist musician. I love building clean, easy-to-use apps, whether that’s full-stack web projects with React and Node or native Android apps with Kotlin and Jetpack Compose. I enjoy taking ideas from a rough sketch to a polished, working product — and I’m always experimenting with new tech and creative projects along the way.

Outside code, I have a deep interest in music — which keeps my creativity sharp and helps me think about rhythm and structure in software. Finally, I am a devoted Christian and my faith in the Lord Jesus Christ is most important to me.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(20.dp))

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
