package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
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
        when (val uiState = state.providersUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading providers...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load providers",
                    error = uiState.error,
                    onRetry = { actions.onLoadProviders() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val providers = uiState.data

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: Providers List
                    ProvidersListPanel(
                        providers = providers,
                        selectedProvider = state.selectedProvider,
                        onProviderSelected = { actions.onSelectProvider(it) },
                        onAddNewProvider = { actions.onStartAddingNewProvider() },
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

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Click to load providers",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadProviders() }) {
                            Text("Load Providers")
                        }
                    }
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
}
