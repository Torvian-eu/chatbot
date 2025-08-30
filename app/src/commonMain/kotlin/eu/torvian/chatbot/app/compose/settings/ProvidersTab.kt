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
import eu.torvian.chatbot.app.domain.contracts.FormMode
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

    // Update selectedProvider when the providers list changes
    LaunchedEffect(state.providersUiState) {
        selectedProvider?.let { selected ->
            // If we have a selected provider, find the updated version in the providers list
            val providers = state.providersUiState.dataOrNull
            if (providers != null) {
                val updatedProvider = providers.find { it.id == selected.id }
                if (updatedProvider != null && updatedProvider != selected) {
                    selectedProvider = updatedProvider
                }
            }
        }
    }

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

    // Unified Provider Form Dialog (for both adding and editing)
    if (state.isEditingProvider) {
        when (state.providerForm.mode) {
            FormMode.NEW -> {
                AddProviderDialog(
                    formState = state.providerForm,
                    onNameChange = { name -> actions.onUpdateProviderForm { it.copy(name = name) } },
                    onTypeChange = { type -> actions.onUpdateProviderForm { it.copy(type = type) } },
                    onBaseUrlChange = { baseUrl -> actions.onUpdateProviderForm { it.copy(baseUrl = baseUrl) } },
                    onDescriptionChange = { description -> actions.onUpdateProviderForm { it.copy(description = description) } },
                    onCredentialChange = { credential -> actions.onUpdateProviderForm { it.copy(credential = credential) } },
                    onConfirm = { actions.onSaveProviderForm() },
                    onDismiss = { actions.onCancelProviderForm() }
                )
            }
            FormMode.EDIT -> {
                EditProviderDialog(
                    originalProviderName = state.editingProvider?.name ?: "Unknown Provider",
                    formState = state.providerForm,
                    credentialUpdateLoading = state.credentialUpdateLoading,
                    onNameChange = { name -> actions.onUpdateProviderForm { it.copy(name = name) } },
                    onTypeChange = { type -> actions.onUpdateProviderForm { it.copy(type = type) } },
                    onBaseUrlChange = { baseUrl -> actions.onUpdateProviderForm { it.copy(baseUrl = baseUrl) } },
                    onDescriptionChange = { description -> actions.onUpdateProviderForm { it.copy(description = description) } },
                    onNewCredentialInputChange = { credential -> actions.onUpdateProviderForm { it.copy(credential = credential) } },
                    onUpdateProvider = { actions.onSaveProviderForm() },
                    onUpdateCredential = { actions.onUpdateProviderCredential() },
                    onDismiss = { actions.onCancelProviderForm() }
                )
            }
        }
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
