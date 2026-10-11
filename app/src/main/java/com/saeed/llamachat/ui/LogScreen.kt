package com.saeed.llamachat.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeed.llamachat.core.RunLog
import java.io.File

/**
 * The in-app log viewer.
 *
 * This screen is not a convenience. Without a PC, without Android Studio and without logcat,
 * this is the only place the app's behaviour can be observed at all — for Saeed and for the
 * agent both. It refreshes itself once a second while the current run is selected, and it can
 * show every previous run because the files are kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit, onShare: (Intent) -> Unit) {
    var selected by remember { mutableStateOf(RunLog.currentFile()) }
    var refreshToken by remember { mutableStateOf(0) }
    var sessions by remember { mutableStateOf(RunLog.sessions()) }

    // A one-second ticker, done with a Handler rather than coroutines so this screen adds no
    // dependency and cannot be the reason something else fails to build.
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                refreshToken++
                sessions = RunLog.sessions()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(ticker, 1000)
        onDispose { handler.removeCallbacks(ticker) }
    }

    val content = remember(selected, refreshToken) { RunLog.read(selected) }
    val scrollState = rememberScrollState()

    // Follow the tail, which is where the newest events are.
    LaunchedEffect(content) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: "Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshToken++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { onShare(RunLog.shareIntent(selected)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            RunLog.unavailableReason?.let { reason ->
                Text(
                    text = "The log could not be written: $reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            if (sessions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sessions.forEach { session ->
                        SessionButton(
                            session = session,
                            isSelected = session == selected,
                            onClick = { selected = session },
                        )
                    }
                }
            }

            Text(
                text = "${sessions.size} run(s) kept · live, refreshed every second · " +
                    "written to disk as it happens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            Text(
                text = content,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

/** The file name carries the start time: `run-2026-10-11_01-45-02.log`. */
@Composable
private fun SessionButton(session: File, isSelected: Boolean, onClick: () -> Unit) {
    val label = session.name
        .removePrefix("run-")
        .removeSuffix(".log")
        .substringAfter('_', session.name)

    if (isSelected) {
        Button(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelSmall) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}