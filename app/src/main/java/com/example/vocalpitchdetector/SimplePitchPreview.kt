package com.example.vocalpitchdetector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SimplePitchPreview(samples: List<PitchSample>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(8.dp)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            items(samples) { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "${s.tMs}")
                    Text(text = if (s.midi != null && !s.midi.isNaN()) String.format("%.1f Hz / %.1f", s.freq, s.midi) else String.format("%.1f Hz", s.freq))
                }
            }
        }
    }
}
