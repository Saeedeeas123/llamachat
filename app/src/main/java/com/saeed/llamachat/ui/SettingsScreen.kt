package com.saeed.llamachat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saeed.llamachat.core.ChatController
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = ChatController.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { ChatController.resetSettings() }) { Text("Reset") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Apply-on-load settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Context size and thread count are fixed when a model is loaded. " +
                            "Changing them here takes effect the next time you load a model. " +
                            "Everything else applies to the very next message.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionTitle("Runtime")

            IntSlider(
                label = "Context size",
                value = settings.contextSize,
                range = 512..8192,
                step = 512,
                valueText = "${settings.contextSize} tokens",
                onValue = { ChatController.updateSettings(settings.copy(contextSize = it)) },
            )
            IntSlider(
                label = "CPU threads",
                value = settings.threads,
                range = 1..8,
                step = 1,
                valueText = "${settings.threads}",
                onValue = { ChatController.updateSettings(settings.copy(threads = it)) },
            )

            SectionTitle("Generation")

            IntSlider(
                label = "Max new tokens",
                value = settings.maxTokens,
                range = 32..1024,
                step = 32,
                valueText = "${settings.maxTokens}",
                onValue = { ChatController.updateSettings(settings.copy(maxTokens = it)) },
            )
            FloatSlider(
                label = "Temperature",
                value = settings.temperature,
                range = 0f..1.5f,
                valueText = String.format(Locale.US, "%.2f", settings.temperature),
                hint = "0 = always picks the most likely token",
                onValue = { ChatController.updateSettings(settings.copy(temperature = it)) },
            )
            IntSlider(
                label = "Top-K",
                value = settings.topK,
                range = 0..100,
                step = 1,
                valueText = if (settings.topK == 0) "off" else "${settings.topK}",
                onValue = { ChatController.updateSettings(settings.copy(topK = it)) },
            )
            FloatSlider(
                label = "Top-P",
                value = settings.topP,
                range = 0.1f..1f,
                valueText = String.format(Locale.US, "%.2f", settings.topP),
                onValue = { ChatController.updateSettings(settings.copy(topP = it)) },
            )
            FloatSlider(
                label = "Repeat penalty",
                value = settings.repeatPenalty,
                range = 1f..1.5f,
                valueText = String.format(Locale.US, "%.2f", settings.repeatPenalty),
                hint = "Higher discourages repeating the same phrase",
                onValue = { ChatController.updateSettings(settings.copy(repeatPenalty = it)) },
            )

            SectionTitle("System prompt")

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { ChatController.updateSettings(settings.copy(systemPrompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("Optional instructions sent before every conversation") },
            )

            SectionTitle("Build")

            Text(
                text = "llama.cpp: " + (ChatController.nativeVersion ?: "native library unavailable"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChatController.nativeLoadError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: String,
    onValue: (Int) -> Unit,
) {
    val steps = ((range.last - range.first) / step) - 1
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = (raw / step).roundToInt() * step
                onValue(snapped.coerceIn(range.first, range.last))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
        )
    }
}

@Composable
private fun FloatSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    hint: String? = null,
    onValue: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}