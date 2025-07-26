package eu.torvian.chatbot.app.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.torvian.chatbot.app.viewmodel.StartupUiState
import eu.torvian.chatbot.app.viewmodel.StartupViewModel
import eu.torvian.chatbot.server.main.ServerConfig
import eu.torvian.chatbot.server.main.ServerControlServiceImpl


/**
 * Root Composable that manages the overall application UI state based on startup status.
 * Uses the [StartupViewModel] to observe the server's startup state and displays
 * appropriate UI based on the current state.
 */
@Composable
fun Old_AppShell() {
    val startupViewModel = viewModel {
        StartupViewModel(
            ServerControlServiceImpl(
                // TODO: Load server config from application.conf
                ServerConfig("http", "localhost", 8080, "")
            )
        )
    }

    val startupState by startupViewModel.startupState.collectAsState()

    LaunchedEffect(Unit) {
        startupViewModel.startApplication()
    }

    val currentState = startupState
    when (currentState) {
        is StartupUiState.NotStarted, is StartupUiState.Starting -> {
            // Show loading screen while server is starting
            MaterialTheme {
                Text("Starting application...")
            }
        }

        is StartupUiState.Started -> {
            // Server is ready, show the main application layout
            MainAppLayout(currentState.serverInstanceInfo)
        }

        is StartupUiState.Error -> {
            // Show error screen
            MaterialTheme {
                Text("Startup Error: ${currentState.exception.message}")
            }
        }

        is StartupUiState.Stopping -> {
            MaterialTheme {
                Text("Shutting down...")
            }
        }

        is StartupUiState.Stopped -> {
            MaterialTheme {
                Text("Server stopped.")
            }
        }
    }
}