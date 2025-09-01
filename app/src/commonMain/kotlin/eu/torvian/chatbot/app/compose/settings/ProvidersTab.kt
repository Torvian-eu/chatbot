package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.ProvidersDialogState

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
                        selectedProvider = state.selectedProvider,
                        onProviderSelected = { actions.onSelectProvider(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: Provider Details/Edit
                    ProviderDetailPanel(
                        provider = state.selectedProvider,
                        onEditProvider = { actions.onStartEditingProvider(it) },
                        onDeleteProvider = { actions.onStartDeletingProvider(it) },
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

    // Dialog handling based on dialog state
    when (val dialogState = state.dialogState) {
        is ProvidersDialogState.None -> {
            // No dialog open
        }

        is ProvidersDialogState.AddNewProvider -> {
            AddProviderDialog(
                formState = dialogState.formState,
                onNameChange = { name -> actions.onUpdateProviderForm { it.copy(name = name) } },
                onTypeChange = { type -> actions.onUpdateProviderForm { it.copy(type = type) } },
                onBaseUrlChange = { baseUrl -> actions.onUpdateProviderForm { it.copy(baseUrl = baseUrl) } },
                onDescriptionChange = { description -> actions.onUpdateProviderForm { it.copy(description = description) } },
                onCredentialChange = { credential -> actions.onUpdateProviderForm { it.copy(credential = credential) } },
                onConfirm = { actions.onSaveProvider() },
                onDismiss = { actions.onCancelDialog() }
            )
        }

        is ProvidersDialogState.EditProvider -> {
            EditProviderDialog(
                originalProviderName = dialogState.provider.name,
                formState = dialogState.formState,
                credentialUpdateLoading = dialogState.isUpdatingCredential,
                onNameChange = { name -> actions.onUpdateProviderForm { it.copy(name = name) } },
                onTypeChange = { type -> actions.onUpdateProviderForm { it.copy(type = type) } },
                onBaseUrlChange = { baseUrl -> actions.onUpdateProviderForm { it.copy(baseUrl = baseUrl) } },
                onDescriptionChange = { description -> actions.onUpdateProviderForm { it.copy(description = description) } },
                onNewCredentialInputChange = { credential -> actions.onUpdateProviderForm { it.copy(credential = credential) } },
                onUpdateProvider = { actions.onSaveProvider() },
                onUpdateCredential = { actions.onUpdateProviderCredential() },
                onDismiss = { actions.onCancelDialog() }
            )
        }

        is ProvidersDialogState.DeleteProvider -> {
            DeleteProviderConfirmationDialog(
                provider = dialogState.provider,
                onConfirm = {
                    actions.onDeleteProvider(dialogState.provider.id)
                },
                onDismiss = { actions.onCancelDialog() }
            )
        }
    }
}
