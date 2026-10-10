package com.saeed.llamachat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.llamachat.core.ChatController
import com.saeed.llamachat.core.EngineState
import com.saeed.llamachat.core.ModelStore
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    val models = ChatController.models
    val scanning = ChatController.scanning
    val importing = ChatController.importing
    val progress = ChatController.importProgress
    val engine = ChatController.engine
    val hasAccess = ChatController.hasAllFilesAccess()

    // Rescanning on entry also picks up a permission the user just granted.
    LaunchedEffect(Unit) { ChatController.rescanModels() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { LoadedModelCard(engine) }

            if (!hasAccess) {
                item { AccessCard(onGrantAccess) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { ChatController.rescanModels() }, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanning) "Scanning…" else "Rescan")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onPickFile, enabled = !importing) {
                        Text(if (importing) "Importing…" else "Import file")
                    }
                }
            }

            if (importing) {
                item {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Copied ${humanSize(progress)} into app storage",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            if (scanning && models.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Looking for .gguf files…")
                    }
                }
            }

            if (!scanning && models.isEmpty()) {
                item { EmptyCard(hasAccess) }
            }

            items(models) { model ->
                ModelRow(
                    model = model,
                    isLoaded = ChatController.loadedModelName == model.name,
                    busy = engine is EngineState.Loading,
                )
            }

            item {
                Text(
                    text = "Searched: Models, Download, Documents (two levels deep), plus " +
                        "everything imported into the app. Tap a file to load it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadedModelCard(engine: EngineState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Loaded model", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            when (engine) {
                is EngineState.Ready -> {
                    Text(engine.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { ChatController.unloadModel() }) {
                        Text("Unload")
                    }
                }
                is EngineState.Loading -> Text("Loading ${engine.name}…")
                is EngineState.Failed -> Text(
                    text = engine.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is EngineState.NoModel -> Text(
                    text = "Nothing loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccessCard(onGrantAccess: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("All-files access is off", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Without it, only models already imported into the app are listed. " +
                    "Granting it lets LlamaChat load .gguf files straight from shared storage " +
                    "instead of copying them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGrantAccess) { Text("Grant access") }
        }
    }
}

@Composable
private fun EmptyCard(hasAccess: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("No .gguf files found", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasAccess) {
                    "Put a .gguf file in /Models or /Download, or use Import file."
                } else {
                    "Use Import file, or grant all-files access to search shared storage."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModelRow(model: ModelStore.ModelFile, isLoaded: Boolean, busy: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                ChatController.loadModel(model.path, model.name)
            },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name + if (isLoaded) "  • loaded" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = humanSize(model.sizeBytes) + if (model.imported) "  • imported" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = model.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.imported) {
                TextButton(onClick = { ChatController.deleteModel(model) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
}