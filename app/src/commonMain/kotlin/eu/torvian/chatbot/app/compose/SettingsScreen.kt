package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.UiStateContent
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.app.viewmodel.SettingsConfigViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * A stateful wrapper Composable for the Settings screen UI.
 *
 * This screen provides a tabbed interface for managing:
 * - LLM Providers (E4.S8-S12, E5.S4)
 * - LLM Models (E4.S1-S4)
 * - Model Settings Profiles (E4.S5-S6)
 *
 * It obtains its own ViewModels and manages the overall layout and navigation
 * between the different configuration sections.
 */
@Composable
fun SettingsScreen(
    providerConfigViewModel: ProviderConfigViewModel = koinViewModel(),
    modelConfigViewModel: ModelConfigViewModel = koinViewModel(),
    settingsConfigViewModel: SettingsConfigViewModel = koinViewModel()
) {
    // Tab state management
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Providers", "Models", "Settings")

    // Load initial data for all ViewModels
    LaunchedEffect(Unit) {
        providerConfigViewModel.loadProviders()
        modelConfigViewModel.loadModelsAndProviders()
        settingsConfigViewModel.loadModels()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab navigation
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )
            }
        }

        // Tab content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (selectedTabIndex) {
                0 -> ProvidersTab(providerConfigViewModel)
                1 -> ModelsTab(modelConfigViewModel)
                2 -> SettingsTab(settingsConfigViewModel)
            }
        }
    }
}

/**
 * Providers management tab with ViewModel integration.
 * Demonstrates proper state collection and error handling.
 * Full implementation will be completed in Phase 2.
 */
@Composable
private fun ProvidersTab(viewModel: ProviderConfigViewModel) {
    val providersState by viewModel.providersState.collectAsState()
    val isAddingNewProvider by viewModel.isAddingNewProvider.collectAsState()

    UiStateContent(
        uiState = providersState,
        onRetry = { viewModel.loadProviders() },
        loadingMessage = "Loading providers...",
        errorTitle = "Failed to load providers"
    ) { providers ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Providers (${providers.size})",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (providers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "No providers configured.\nFull UI will be implemented in Phase 2.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Found ${providers.size} providers:\n${providers.joinToString("\n") { "• ${it.name} (${it.type})" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

/**
 * Models management tab with ViewModel integration.
 * Demonstrates proper state collection and error handling.
 * Full implementation will be completed in Phase 3.
 */
@Composable
private fun ModelsTab(viewModel: ModelConfigViewModel) {
    val modelsState by viewModel.modelsState.collectAsState()
    val providersState by viewModel.providersForSelection.collectAsState()

    UiStateContent(
        uiState = modelsState,
        onRetry = { viewModel.loadModelsAndProviders() },
        loadingMessage = "Loading models...",
        errorTitle = "Failed to load models"
    ) { models ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Models (${models.size})",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (models.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "No models configured.\nFull UI will be implemented in Phase 3.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Found ${models.size} models:\n${models.joinToString("\n") { "• ${it.name} (${it.displayName ?: "No display name"})" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

/**
 * Settings management tab with ViewModel integration.
 * Demonstrates proper state collection and error handling.
 * Full implementation will be completed in Phase 4.
 */
@Composable
private fun SettingsTab(viewModel: SettingsConfigViewModel) {
    val modelsState by viewModel.modelsForSelection.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Model Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        UiStateContent(
            uiState = modelsState,
            onRetry = { viewModel.loadModels() },
            loadingMessage = "Loading models for settings...",
            errorTitle = "Failed to load models"
        ) { models ->
            if (models.isEmpty()) {
                Text(
                    text = "No models available for settings configuration.\nFull UI will be implemented in Phase 4.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Selected Model ID: ${selectedModelId ?: "None"}\n" +
                            "Available models: ${models.size}\n" +
                            "Settings profiles: ${if (settingsState.isSuccess) settingsState.dataOrNull?.size ?: 0 else "Loading..."}\n\n" +
                            "Full settings management UI will be implemented in Phase 4.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}