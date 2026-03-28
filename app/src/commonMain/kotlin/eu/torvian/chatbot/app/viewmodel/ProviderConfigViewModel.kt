package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ProvidersDialogState
import eu.torvian.chatbot.app.repository.ProviderRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserGroupRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.api.llm.DiscoveredProviderModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Manages the UI state and logic for configuring LLM Providers (E4.S*, E5.S*).
 *
 * This ViewModel handles:
 * - Loading and displaying a list of configured LLM providers (E4.S9).
 * - Managing the state for adding new providers (E4.S8).
 * - Managing the state for editing existing provider details (E4.S10).
 * - Managing the state for updating/removing provider API credentials (E4.S12).
 * - Deleting providers (E4.S11).
 * - Managing provider access control (public/private, grant/revoke access).
 * - Communicating with the backend via [ProviderRepository].
 *
 * @constructor
 * @param providerRepository The repository for provider-related operations.
 * @param userGroupRepository The repository for user group-related operations.
 * @param notificationService Service for notifications and error handling.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property providersState The state of the list of all configured LLM providers (E4.S9).
 * @property selectedProvider The currently selected provider in the master-detail UI.
 * @property dialogState The current dialog state for the providers tab.
 */
class ProviderConfigViewModel(
    private val providerRepository: ProviderRepository,
    private val userGroupRepository: UserGroupRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val prettyJson = Json {
        prettyPrint = true
    }

    // --- Private State Properties ---

    private val userSelectedProviderId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<ProvidersDialogState>(ProvidersDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all configured LLM providers (E4.S9).
     * Includes loading, success with data, or error states.
     */
    val providersState: StateFlow<DataState<RepositoryError, List<LLMProviderDetails>>> =
        providerRepository.providersDetails

    /**
     * The currently selected provider in the master-detail UI.
     */
    val selectedProvider: StateFlow<LLMProviderDetails?> = combine(
        providersState.map { it.dataOrNull },
        userSelectedProviderId
    ) { providersList, currentSelectedId ->
        providersList?.find { it.provider.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The current dialog state for the providers tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<ProvidersDialogState> = _dialogState.asStateFlow()

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM providers from the backend (E4.S9).
     *
     * With the repository pattern, this method triggers loading in the repository.
     * The reactive data streams in the init block will automatically update the UI state.
     */
    fun loadProviders() {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.loadProvidersDetails()
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load providers"
                    )
                }
        }
    }

    /**
     * Selects a provider (or clears selection when null).
     */
    fun selectProvider(providerDetails: LLMProviderDetails?) {
        userSelectedProviderId.value = providerDetails?.provider?.id
    }

    /**
     * Initiates the process of adding a new provider by showing the form (E4.S8).
     */
    fun startAddingNewProvider() {
        _dialogState.value = ProvidersDialogState.AddNewProvider()
    }

    /**
     * Initiates the editing process for an existing provider (E4.S10).
     * This now handles both general provider editing and credential updates.
     *
     * @param provider The [LLMProvider] to be edited.
     */
    fun startEditingProvider(provider: LLMProvider) {
        _dialogState.value = ProvidersDialogState.EditProvider(
            provider = provider,
            formState = ProviderFormState.fromProvider(provider)
        )
    }

    /**
     * Initiates the deletion process for a provider by showing the confirmation dialog.
     *
     * @param provider The [LLMProvider] to be deleted.
     */
    fun startDeletingProvider(provider: LLMProvider) {
        _dialogState.value = ProvidersDialogState.DeleteProvider(provider)
    }

    /**
     * Updates any field in the provider form using a lambda function.
     */
    fun updateProviderForm(update: (ProviderFormState) -> ProviderFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is ProvidersDialogState.AddNewProvider -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                is ProvidersDialogState.EditProvider -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState // No change for other states
            }
        }
    }

    /**
     * Saves the provider form data. Behavior depends on the dialog state:
     * - AddNewProvider: Creates a new provider
     * - EditProvider: Updates provider details (not credentials)
     */
    fun saveProvider() {
        when (val dialogState = _dialogState.value) {
            is ProvidersDialogState.AddNewProvider -> addNewProvider(dialogState)
            is ProvidersDialogState.EditProvider -> saveEditedProviderDetails(dialogState)
            else -> return
        }
    }

    /**
     * Updates the API key credential for the provider in EditProvider dialog state (E4.S12).
     */
    fun updateProviderCredential() {
        val dialogState = _dialogState.value
        if (dialogState !is ProvidersDialogState.EditProvider) return

        val form = dialogState.formState
        val provider = dialogState.provider

        // Take non-blank credential, otherwise send null to remove it
        val credential = form.credential.takeIf { it.isNotBlank() }

        viewModelScope.launch(uiDispatcher) {
            // Set loading state
            _dialogState.value = dialogState.copy(isUpdatingCredential = true)

            providerRepository.updateProviderCredential(provider.id, credential)
                .fold(
                    ifLeft = { error ->
                        _dialogState.update { state ->
                            if (state is ProvidersDialogState.EditProvider) {
                                state.copy(
                                    isUpdatingCredential = false,
                                    formState = state.formState.withError("Error updating credential: ${error.message}")
                                )
                            } else state
                        }
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to update provider credential"
                        )
                    },
                    ifRight = {
                        // Credential updated successfully. With repository pattern, we don't need to manually refresh
                        // the provider list since credentials are stored separately and don't affect provider metadata.
                        // Clear the credential input and reset loading state
                        _dialogState.update { state ->
                            if (state is ProvidersDialogState.EditProvider) {
                                state.copy(
                                    isUpdatingCredential = false,
                                    formState = state.formState.copy(credential = "")
                                )
                            } else state
                        }
                    }
                )
        }
    }

    /**
     * Tests provider connection from the add-provider dialog using current unsaved form values.
     */
    fun testProviderConnectionInDialog() {
        val currentState = _dialogState.value
        val form = (currentState as? ProvidersDialogState.AddNewProvider)?.formState ?: return

        if (form.baseUrl.isBlank()) {
            updateProviderForm { it.withError("Base URL cannot be empty.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            _dialogState.update { state ->
                when (state) {
                    is ProvidersDialogState.AddNewProvider -> state.copy(
                        isTestingConnection = true,
                        formState = state.formState.withError(null)
                    )
                    else -> state
                }
            }

            providerRepository.testProviderConnection(
                baseUrl = form.baseUrl.trim(),
                type = form.type,
                credential = form.credential.takeIf { it.isNotBlank() }
            ).fold(
                ifLeft = { error ->
                    _dialogState.update { state ->
                        when (state) {
                            is ProvidersDialogState.AddNewProvider -> state.copy(
                                isTestingConnection = false,
                                formState = state.formState.withError("Connection test failed: ${error.message}")
                            )
                            else -> state
                        }
                    }
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Provider connection test failed"
                    )
                },
                ifRight = { discoveredModels ->
                    _dialogState.update { state ->
                        when (state) {
                            is ProvidersDialogState.AddNewProvider -> state.copy(
                                isTestingConnection = false,
                                formState = state.formState.withError(null)
                            )

                            else -> state
                        }
                    }
                    notificationService.genericSuccess(
                        shortMessage = "Connected - ${discoveredModels.size} model(s) discovered"
                    )
                }
            )
        }
    }

    /**
     * Deletes a specific LLM provider configuration (E4.S11).
     *
     * @param providerId The ID of the provider to delete.
     */
    fun deleteProvider(providerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.deleteProvider(providerId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete provider"
                        )
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Lists discovered models for a provider and opens a read-only raw JSON dialog.
     */
    fun listProviderModels(providerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.discoverProviderModels(providerId).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to discover provider models"
                    )
                },
                ifRight = { models ->
                    val providerName = selectedProvider.value?.provider?.takeIf { it.id == providerId }?.name
                        ?: "Provider #$providerId"
                    val rawJson = prettyJson.encodeToString(
                        ListSerializer(DiscoveredProviderModel.serializer()),
                        models
                    )
                    _dialogState.value = ProvidersDialogState.ShowDiscoveredModelsJson(
                        providerName = providerName,
                        rawJson = rawJson
                    )
                }
            )
        }
    }

    /**
     * Closes any open dialog by setting state to None.
     */
    fun cancelDialog() {
        _dialogState.value = ProvidersDialogState.None
    }

    /**
     * Makes a provider publicly accessible.
     */
    fun makeProviderPublic(providerDetails: LLMProviderDetails) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.makeProviderPublic(providerDetails.provider.id)
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to make provider public"
                    )
                }
        }
    }

    /**
     * Makes a provider private.
     */
    fun makeProviderPrivate(providerDetails: LLMProviderDetails) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.makeProviderPrivate(providerDetails.provider.id)
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to make provider private"
                    )
                }
        }
    }

    /**
     * Opens the manage access dialog for a provider.
     */
    fun openManageAccessDialog(providerDetails: LLMProviderDetails) {
        viewModelScope.launch(uiDispatcher) {
            userGroupRepository.loadGroups().fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load user groups"
                    )
                },
                ifRight = {
                    // Get groups from repository state
                    val groups = userGroupRepository.groups.value.dataOrNull ?: emptyList()

                    // Define available access modes for providers
                    val availableAccessModes = listOf(AccessMode.READ.key, AccessMode.WRITE.key, AccessMode.MANAGE.key)

                    _dialogState.value = ProvidersDialogState.ManageAccess(
                        providerDetails = providerDetails,
                        availableGroups = groups,
                        grantAccessForm = GrantAccessFormState(
                            selectedAccessMode = availableAccessModes.first(),
                            availableAccessModes = availableAccessModes
                        )
                    )
                }
            )
        }
    }

    /**
     * Opens the grant access dialog within the manage access dialog.
     */
    fun openGrantAccessDialog() {
        _dialogState.update { state ->
            if (state is ProvidersDialogState.ManageAccess) {
                state.copy(showGrantDialog = true)
            } else state
        }
    }

    /**
     * Closes the grant access dialog.
     */
    fun closeGrantAccessDialog() {
        _dialogState.update { state ->
            if (state is ProvidersDialogState.ManageAccess) {
                state.copy(
                    showGrantDialog = false,
                    grantAccessForm = GrantAccessFormState() // Reset form
                )
            } else state
        }
    }

    /**
     * Updates the grant access form state.
     */
    fun updateGrantAccessForm(update: (GrantAccessFormState) -> GrantAccessFormState) {
        _dialogState.update { state ->
            if (state is ProvidersDialogState.ManageAccess) {
                state.copy(grantAccessForm = update(state.grantAccessForm))
            } else state
        }
    }

    /**
     * Grants access to a provider for a specific user group.
     */
    fun grantProviderAccess(providerId: Long, groupId: Long, accessMode: String) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.grantProviderAccess(
                providerId,
                groupId,
                accessMode
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to grant access"
                    )
                },
                ifRight = { providerDetails ->
                    // Refresh the dialog state to reflect the new access details
                    _dialogState.update { state ->
                        if (state is ProvidersDialogState.ManageAccess) {
                            state.copy(
                                providerDetails = providerDetails
                            )
                        } else state
                    }
                    closeGrantAccessDialog()
                }
            )
        }
    }

    /**
     * Revokes access to a provider from a specific user group.
     */
    fun revokeProviderAccess(providerId: Long, groupId: Long, accessMode: String) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.revokeProviderAccess(
                providerId,
                groupId,
                accessMode
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to revoke access"
                    )
                },
                ifRight = { providerDetails ->
                    // Refresh the dialog state to reflect the new access details
                    _dialogState.update { state ->
                        if (state is ProvidersDialogState.ManageAccess) {
                            state.copy(
                                providerDetails = providerDetails
                            )
                        } else state
                    }
                }
            )
        }
    }

    // --- Private Helper Functions ---

    /**
     * Saves the new LLM provider configuration to the backend (E4.S8).
     */
    private fun addNewProvider(dialogState: ProvidersDialogState.AddNewProvider) {
        val form = dialogState.formState

        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            updateProviderForm { it.withError("Name and Base URL cannot be empty.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val credential = form.credential.takeIf { it.isNotBlank() } // Only send if not blank
            providerRepository.addProvider(
                name = form.name.trim(),
                description = form.description.trim(),
                baseUrl = form.baseUrl.trim(),
                type = form.type,
                credential = credential
            )
                .fold(
                    ifLeft = { error ->
                        updateProviderForm { it.withError("Error adding provider: ${error.message}") }
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to add provider"
                        )
                    },
                    ifRight = { newProviderDetails ->
                        cancelDialog()
                        selectProvider(newProviderDetails)
                    }
                )
        }
    }

    /**
     * Saves the general details (name, description, base URL, type) of an edited provider (E4.S10).
     * This does NOT update the API key.
     */
    private fun saveEditedProviderDetails(dialogState: ProvidersDialogState.EditProvider) {
        val form = dialogState.formState
        val originalProvider = dialogState.provider

        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            updateProviderForm { it.withError("Name and Base URL cannot be empty.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val updatedProvider = originalProvider.copy(
                name = form.name.trim(),
                description = form.description.trim(),
                baseUrl = form.baseUrl.trim(),
                type = form.type
            )
            providerRepository.updateProvider(updatedProvider)
                .fold(
                    ifLeft = { error ->
                        updateProviderForm { it.withError("Error updating provider: ${error.message}") }
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to update provider"
                        )
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }
}
