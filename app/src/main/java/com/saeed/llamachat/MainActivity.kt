package com.saeed.llamachat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.saeed.llamachat.core.ChatController
import com.saeed.llamachat.ui.ChatScreen
import com.saeed.llamachat.ui.LlamaChatTheme
import com.saeed.llamachat.ui.LogScreen
import com.saeed.llamachat.ui.ModelsScreen
import com.saeed.llamachat.ui.SettingsScreen

/**
 * Single activity, three screens, no navigation library.
 *
 * The three screens are a plain enum held in composition state: with no deep links, no
 * back stack requirements and no saved-state contract to honour, a navigation dependency
 * would be cost without benefit.
 */
class MainActivity : ComponentActivity() {

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ChatController.importModel(uri)
        }

    private val grantAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The user may have just flipped all-files access; refresh the list either way.
            ChatController.rescanModels()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatController.init(applicationContext)

        setContent {
            LlamaChatTheme {
                var screen by remember { mutableStateOf(Screen.Chat) }

                BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }

                when (screen) {
                    Screen.Chat -> ChatScreen(
                        onOpenModels = { screen = Screen.Models },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenLog = { screen = Screen.Log },
                    )

                    Screen.Models -> ModelsScreen(
                        onBack = { screen = Screen.Chat },
                        onPickFile = { pickModel.launch(arrayOf("*/*")) },
                        onGrantAccess = {
                            ChatController.allFilesAccessIntent()?.let { grantAccess.launch(it) }
                        },
                    )

                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Chat })

                    Screen.Log -> LogScreen(
                        onBack = { screen = Screen.Chat },
                        onShare = { intent ->
                            startActivity(Intent.createChooser(intent, "Share log"))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A permission granted in system settings only becomes visible on resume.
        ChatController.rescanModels()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the model deterministically when the user really leaves, instead of
        // leaving a multi-gigabyte mapping for the system to reclaim later.
        if (isFinishing) ChatController.shutdown()
    }
}

private enum class Screen { Chat, Models, Settings, Log }