package com.example.vocalpitchdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SmallTopAppBarCompat(
    titleContent: @Composable () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    // Use tonalElevation / shadowElevation for Material3 Surface (no `elevation` param)
    Surface(tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row { titleContent() }
            Row { actions() }
        }
    }
}
