package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.app.compose.settings.dialogs.ManageAccessDialog
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ProvidersDialogState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Providers management tab with list and detail pages.
 *
 * The tab still owns only presentational branching; page navigation state lives in
 * the route so dialog state and item-detail navigation stay separate.
 * Implements Epic 4 user stories: E4.S8-S12 and Epic 5: E5.S4.
 */
@Composable
fun ProvidersTab(
    state: ProvidersTabState,
    actions: ProvidersTabActions,
    authState: AuthState.Authenticated,
    selectedProviderId: Long?,
    onOpenProviderDetails: (LLMProviderDetails) -> Unit,
    onBackToProviderList: () -> Unit,
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
                val activeProviderDetails = providers.firstOrNull { it.provider.id == selectedProviderId }

                if (activeProviderDetails != null) {
                    ProviderDetailsPage(
                        providerDetails = activeProviderDetails,
                        onBackToList = onBackToProviderList,
                        onEditProvider = { actions.onStartEditingProvider(it) },
                        onDeleteProvider = { actions.onStartDeletingProvider(it) },
                        onListModels = { actions.onListProviderModels(it.provider.id) },
                        onMakePublic = { actions.onMakeProviderPublic(it) },
                        onMakePrivate = { actions.onMakeProviderPrivate(it) },
                        onManageAccess = { actions.onOpenManageAccessDialog(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ProvidersListPage(
                        providers = providers,
                        selectedProvider = state.selectedProvider,
                        onProviderSelected = { providerDetails ->
                            actions.onSelectProvider(providerDetails)
                            onOpenProviderDetails(providerDetails)
                        },
                        onAddNewProvider = { actions.onStartAddingNewProvider() },
                        authState = authState,
                        modifier = Modifier.fillMaxSize()
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
                    connectionTestLoading = dialogState.isTestingConnection,
                    onNameChange = { name -> actions.onUpdateProviderForm { it.copy(name = name) } },
                    onTypeChange = { type -> actions.onUpdateProviderForm { it.copy(type = type) } },
                    onBaseUrlChange = { baseUrl -> actions.onUpdateProviderForm { it.copy(baseUrl = baseUrl) } },
                    onDescriptionChange = { description -> actions.onUpdateProviderForm { it.copy(description = description) } },
                    onCredentialChange = { credential -> actions.onUpdateProviderForm { it.copy(credential = credential) } },
                    onTestConnection = { actions.onTestProviderConnectionInDialog() },
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

            is ProvidersDialogState.ShowDiscoveredModelsJson -> {
                val scrollState = rememberScrollState()
                AlertDialog(
                    onDismissRequest = actions::onCancelDialog,
                    title = {
                        Text("Discovered Models - ${dialogState.providerName}")
                    },
                    text = {
                        ScrollbarWrapper(
                            scrollState = scrollState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 240.dp, max = 480.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = dialogState.rawJson,
                                    modifier = Modifier.verticalScroll(scrollState),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = actions::onCancelDialog) {
                            Text("Close")
                        }
                    }
                )
            }

            is ProvidersDialogState.ManageAccess -> {
                val provider = dialogState.providerDetails.provider
                ManageAccessDialog(
                        resourceName = provider.name,
                        accessDetails = dialogState.providerDetails.accessDetails,
                        availableGroups = dialogState.availableGroups,
                        showGrantDialog = dialogState.showGrantDialog,
                        grantAccessForm = dialogState.grantAccessForm,
                        onOpenGrantDialog = actions::onOpenGrantAccessDialog,
                        onCloseGrantDialog = actions::onCloseGrantAccessDialog,
                        onUpdateGrantForm = actions::onUpdateGrantAccessForm,
                        onConfirmGrant = { groupId, accessMode ->
                            actions.onGrantProviderAccess(
                                provider.id,
                                groupId,
                                accessMode
                            )
                        },
                        onRevokeAccess = { groupId, accessMode ->
                            actions.onRevokeProviderAccess(
                                provider.id,
                                groupId,
                                accessMode
                            )
                        },
                        onDismiss = actions::onCancelDialog
                    )
            }
        }
    }
}
