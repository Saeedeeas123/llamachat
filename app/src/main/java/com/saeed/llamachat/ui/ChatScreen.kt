package com.saeed.llamachat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.llamachat.core.ChatController
import com.saeed.llamachat.core.ChatMessage
import com.saeed.llamachat.core.EngineState
import com.saeed.llamachat.core.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenModels: () -> Unit, onOpenSettings: () -> Unit) {
    val engine = ChatController.engine
    val messages = ChatController.messages
    val streaming = ChatController.streamingText
    val generating = ChatController.generating
    val notice = ChatController.notice

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep the newest message in view while tokens stream in.
    LaunchedEffect(messages.size, streaming.length) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            ChatController.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = ChatController.loadedModelName ?: "LlamaChat",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusLine(engine),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { ChatController.newChat() },
                        enabled = !generating && messages.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Models")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                input = input,
                onInputChange = { input = it },
                generating = generating,
                ready = engine is EngineState.Ready,
                onSend = {
                    val text = input
                    input = ""
                    ChatController.send(text)
                },
                onStop = { ChatController.stopGenerating() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                engine is EngineState.Loading -> LoadingState(engine.name)
                engine is EngineState.NoModel || engine is EngineState.Failed -> NoModelState(engine, onOpenModels)
                messages.isEmpty() -> ReadyHint()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages) { index, message ->
                        val isLast = index == messages.lastIndex
                        val isStreaming = generating && isLast && message.role == Role.ASSISTANT
                        MessageBubble(
                            message = message,
                            displayText = if (isStreaming) streaming else message.text,
                            streaming = isStreaming,
                        )
                    }
                }
            }
        }
    }
}

private fun statusLine(engine: EngineState): String = when (engine) {
    is EngineState.NoModel -> "no model loaded"
    is EngineState.Loading -> "loading ${engine.name}…"
    is EngineState.Ready -> engine.description
    is EngineState.Failed -> engine.message
}

@Composable
private fun MessageBubble(message: ChatMessage, displayText: String, streaming: Boolean) {
    val fromUser = message.role == Role.USER
    val containerColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (fromUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                if (displayText.isEmpty() && streaming) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                } else {
                    SelectionContainer {
                        Text(
                            text = if (streaming) "$displayText▍" else displayText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.width(12.dp))
        Text("Loading $name…", textAlign = TextAlign.Center)
        Text(
            "A large model can take a while to map into memory.",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoModelState(engine: EngineState, onOpenModels: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LlamaChat", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            "Runs GGUF models entirely on this device with llama.cpp. Nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(12.dp))
        if (engine is EngineState.Failed) {
            Text(
                text = engine.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(12.dp))
        }
        Button(onClick = onOpenModels) {
            Text("Choose a model")
        }
    }
}

@Composable
private fun ReadyHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Model ready", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            "Type a message to start. Long-press to select and copy a reply.",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    generating: Boolean,
    ready: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (ready) "Message" else "Load a model first") },
                maxLines = 4,
                enabled = ready && !generating,
            )
            Spacer(Modifier.width(8.dp))
            if (generating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = "Stop", tint = Color.Unspecified)
                }
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                IconButton(onClick = onSend, enabled = ready && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}