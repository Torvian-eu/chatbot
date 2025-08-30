package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * Providers management tab with master-detail layout.
 * Implements Epic 4 user stories: E4.S8-S12 and Epic 5: E5.S4.
 */
@Composable
fun ProvidersTab(
    state: ProvidersTabState,
    actions: ProvidersTabActions,
    modifier: Modifier = Modifier
) {
    var selectedProvider by remember { mutableStateOf<LLMProvider?>(null) }
    var providerToDelete by remember { mutableStateOf<LLMProvider?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.providersUiState.isLoading -> {
                LoadingStateDisplay(
                    message = "Loading providers...",
                    modifier = Modifier.fillMaxSize()
                )
            }
            state.providersUiState.isError -> {
                val error = state.providersUiState.errorOrNull
                if (error != null) {
                    ErrorStateDisplay(
                        title = "Failed to load providers",
                        error = error,
                        onRetry = { actions.onLoadProviders() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Unknown error occurred",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            state.providersUiState.isSuccess -> {
                val providers = state.providersUiState.dataOrNull ?: emptyList()

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: Providers List
                    ProvidersListPanel(
                        providers = providers,
                        selectedProvider = selectedProvider,
                        onProviderSelected = { selectedProvider = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: Provider Details/Edit
                    ProviderDetailPanel(
                        provider = selectedProvider,
                        onEditProvider = { actions.onStartEditingProvider(it) },
                        onDeleteProvider = { providerToDelete = it },
                        onManageCredentials = { actions.onStartEditingProvider(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp)
                    )
                }
            }
            else -> {
                // Idle state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { actions.onLoadProviders() }) {
                        Text("Load Providers")
                    }
                }
            }
        }

        // Floating Action Button for adding new provider (only show when we have data)
        if (state.providersUiState.isSuccess) {
            FloatingActionButton(
                onClick = { actions.onStartAddingNewProvider() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Provider"
                )
            }
        }
    }

    // Add Provider Dialog
    if (state.isAddingNewProvider) {
        AddProviderDialog(
            formState = state.newProviderForm,
            onNameChange = { actions.onUpdateNewProviderName(it) },
            onTypeChange = { actions.onUpdateNewProviderType(it) },
            onBaseUrlChange = { actions.onUpdateNewProviderBaseUrl(it) },
            onDescriptionChange = { actions.onUpdateNewProviderDescription(it) },
            onCredentialChange = { actions.onUpdateNewProviderCredential(it) },
            onConfirm = { actions.onAddNewProvider() },
            onDismiss = { actions.onCancelAddingNewProvider() }
        )
    }

    // Edit Provider Dialog
    state.editingProvider?.let { provider ->
        EditProviderDialog(
            provider = provider,
            formState = state.editingProviderForm,
            credentialUpdateLoading = state.credentialUpdateLoading,
            onNameChange = { actions.onUpdateEditingProviderName(it) },
            onTypeChange = { actions.onUpdateEditingProviderType(it) },
            onBaseUrlChange = { actions.onUpdateEditingProviderBaseUrl(it) },
            onDescriptionChange = { actions.onUpdateEditingProviderDescription(it) },
            onNewCredentialInputChange = { actions.onUpdateEditingProviderNewCredentialInput(it) },
            onUpdateProvider = { actions.onSaveEditedProviderDetails() },
            onUpdateCredential = { actions.onUpdateProviderCredential() },
            onDismiss = { actions.onCancelEditingProvider() }
        )
    }

    // Delete Confirmation Dialog
    providerToDelete?.let { provider ->
        DeleteProviderConfirmationDialog(
            provider = provider,
            onConfirm = {
                actions.onDeleteProvider(provider.id)
                providerToDelete = null
                // Clear selection if we're deleting the selected provider
                if (selectedProvider?.id == provider.id) {
                    selectedProvider = null
                }
            },
            onDismiss = { providerToDelete = null }
        )
    }
}
