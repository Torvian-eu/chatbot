package eu.torvian.chatbot.app.compose.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.config.AppConfigDto
import eu.torvian.chatbot.app.config.ClientConfigLoader
import eu.torvian.chatbot.app.viewmodel.setup.CompleteSetupUseCase
import eu.torvian.chatbot.app.viewmodel.setup.SetupEvent
import eu.torvian.chatbot.app.viewmodel.setup.SetupViewModel

/**
 * Setup screen for initial application configuration.
 *
 * This is a pure UI component - all business logic is in SetupViewModel.
 *
 * @param configDir The directory where configuration files will be saved.
 * @param initialDto Pre-populated configuration data (if any exists).
 * @param onComplete Callback invoked when setup is complete with the validated configuration.
 */
@Composable
fun SetupScreen(
    configDir: String,
    configLoader: ClientConfigLoader,
    initialDto: AppConfigDto,
    onComplete: (AppConfiguration) -> Unit
) {
    // Create ViewModel (manual creation, not Koin - Koin isn't initialized yet)
    val viewModel = remember {
        SetupViewModel(
            configDir = configDir,
            initialDto = initialDto,
            completeSetupUseCase = CompleteSetupUseCase(configLoader)
        )
    }

    // Collect state from ViewModel
    val state by viewModel.state.collectAsState()

    // Handle completion
    LaunchedEffect(state.isComplete) {
        if (state.isComplete && state.completedConfig != null) {
            onComplete(state.completedConfig!!)
        }
    }

    // Pure UI rendering - no business logic
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title
        Text(
            text = "Initial Setup",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Configure your Chatbot application",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Error banner (if any)
        state.errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.onEvent(SetupEvent.DismissError) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Server URL field
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { viewModel.onEvent(SetupEvent.ServerUrlChanged(it)) },
            label = { Text("Server URL") },
            enabled = !state.isLoading,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // Data directory field
        OutlinedTextField(
            value = state.dataDir,
            onValueChange = { viewModel.onEvent(SetupEvent.DataDirChanged(it)) },
            label = { Text("Data Directory") },
            enabled = !state.isLoading,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // Encryption key display (read-only with visibility toggle)
        OutlinedTextField(
            value = state.encryptionKey,
            onValueChange = { },
            label = { Text("Encryption Key (Auto-generated)") },
            readOnly = true,
            enabled = false,
            visualTransformation = if (state.keyVisible)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { viewModel.onEvent(SetupEvent.ToggleKeyVisibility) }) {
                    Icon(
                        imageVector = if (state.keyVisible)
                            Icons.Default.Visibility
                        else
                            Icons.Default.VisibilityOff,
                        contentDescription = if (state.keyVisible)
                            "Hide encryption key"
                        else
                            "Show encryption key"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        // Complete setup button
        Button(
            onClick = { viewModel.onEvent(SetupEvent.CompleteSetup) },
            enabled = state.isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Complete Setup")
            }
        }

        // Info text
        Text(
            text = "Note: The encryption key will be stored securely and used to protect your local data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

