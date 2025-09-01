package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ProvidersDialogState
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.AddProviderRequest
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.UpdateProviderCredentialRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for configuring LLM Providers (E4.S*, E5.S*).
 *
 * This ViewModel handles:
 * - Loading and displaying a list of configured LLM providers (E4.S9).
 * - Managing the state for adding new providers (E4.S8).
 * - Managing the state for editing existing provider details (E4.S10).
 * - Managing the state for updating/removing provider API credentials (E4.S12).
 * - Deleting providers (E4.S11).
 * - Communicating with the backend via [ProviderApi].
 *
 * @constructor
 * @param providerApi The API client for provider-related operations.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property providersState The state of the list of all configured LLM providers (E4.S9).
 * @property selectedProvider The currently selected provider in the master-detail UI.
 * @property dialogState The current dialog state for the providers tab.
 */
class ProviderConfigViewModel(
    private val providerApi: ProviderApi,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val _providersState = MutableStateFlow<UiState<ApiError, List<LLMProvider>>>(UiState.Idle)
    private val _selectedProvider = MutableStateFlow<LLMProvider?>(null)
    private val _dialogState = MutableStateFlow<ProvidersDialogState>(ProvidersDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all configured LLM providers (E4.S9).
     * Includes loading, success with data, or error states.
     */
    val providersState: StateFlow<UiState<ApiError, List<LLMProvider>>> = _providersState.asStateFlow()

    /**
     * The currently selected provider in the master-detail UI.
     */
    val selectedProvider: StateFlow<LLMProvider?> = _selectedProvider.asStateFlow()

    /**
     * The current dialog state for the providers tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<ProvidersDialogState> = _dialogState.asStateFlow()

    // --- Helper Properties ---

    /**
     * Helper property to get the current providers data if in success state, null otherwise.
     */
    private val currentProviders: List<LLMProvider>?
        get() = _providersState.value.dataOrNull

    /**
     * Helper property to check if the providers state is successfully loaded.
     */
    private val isProvidersLoaded: Boolean
        get() = _providersState.value is UiState.Success

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM providers from the backend (E4.S9).
     */
    fun loadProviders() {
        if (_providersState.value.isLoading) return // Prevent duplicate loading
        viewModelScope.launch(uiDispatcher) {
            _providersState.value = UiState.Loading
            providerApi.getAllProviders()
                .fold(
                    ifLeft = { error ->
                        _providersState.value = UiState.Error(error)
                        println("Error loading providers: ${error.code} - ${error.message}")
                    },
                    ifRight = { providers ->
                        _providersState.value = UiState.Success(providers)
                        // Keep selected provider in sync with refreshed list
                        syncSelectedProviderWithList(providers)
                    }
                )
        }
    }

    /**
     * Selects a provider (or clears selection when null).
     */
    fun selectProvider(provider: LLMProvider?) {
        _selectedProvider.value = provider
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
        if (!isProvidersLoaded) return
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
        if (!isProvidersLoaded) return

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

        val providers = currentProviders ?: return
        val form = dialogState.formState
        val provider = dialogState.provider

        // Take non-blank credential, otherwise send null to remove it
        val credential = form.credential.takeIf { it.isNotBlank() }

        viewModelScope.launch(uiDispatcher) {
            // Set loading state
            _dialogState.value = dialogState.copy(isUpdatingCredential = true)

            providerApi.updateProviderCredential(provider.id, UpdateProviderCredentialRequest(credential))
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
                        println("Error updating provider credential: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Credential updated successfully. Refresh the provider in the list.
                        providerApi.getProviderById(provider.id)
                            .fold(
                                ifLeft = { error ->
                                    println("Error refreshing provider after credential update: ${error.code} - ${error.message}. Reloading all providers as fallback.")
                                    loadProviders() // Fallback to full reload if fetching single fails
                                },
                                ifRight = { updatedProviderFromApi ->
                                    val updatedProviders = providers.map {
                                        if (it.id == updatedProviderFromApi.id) updatedProviderFromApi else it
                                    }
                                    _providersState.value = UiState.Success(updatedProviders)
                                    // Sync the selected provider instance if it's the one updated
                                    if (_selectedProvider.value?.id == updatedProviderFromApi.id) {
                                        _selectedProvider.value = updatedProviderFromApi
                                    }
                                }
                            )
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
     * Deletes a specific LLM provider configuration (E4.S11).
     *
     * @param providerId The ID of the provider to delete.
     */
    fun deleteProvider(providerId: Long) {
        if (!isProvidersLoaded) return

        viewModelScope.launch(uiDispatcher) {
            val providers = currentProviders ?: return@launch

            providerApi.deleteProvider(providerId)
                .fold(
                    ifLeft = { error ->
                        // Present the error to the user. For a conflict error (RESOURCE_IN_USE),
                        // provide more specific feedback as per E4.S11.
                        val errorMessage = when (error.code) {
                            CommonApiErrorCodes.RESOURCE_IN_USE.code ->
                                "Cannot delete provider. It is currently linked to one or more models. Please delete associated models first."

                            else -> "Error deleting provider: ${error.message}"
                        }
                        println(errorMessage) // For now, logging to console
                    },
                    ifRight = {
                        // Remove the deleted provider from the list (E4.S11)
                        val updatedProviders = providers.filter { it.id != providerId }
                        _providersState.value = UiState.Success(updatedProviders)
                        // Clear selection if we're deleting the selected provider
                        if (_selectedProvider.value?.id == providerId) {
                            _selectedProvider.value = null
                        }
                        // Close any open dialog
                        cancelDialog()
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
            val request = AddProviderRequest(
                name = form.name.trim(),
                description = form.description.trim(),
                baseUrl = form.baseUrl.trim(),
                type = form.type,
                credential = form.credential.takeIf { it.isNotBlank() } // Only send if not blank
            )
            val providers = currentProviders

            providerApi.addProvider(request)
                .fold(
                    ifLeft = { error ->
                        updateProviderForm { it.withError("Error adding provider: ${error.message}") }
                        println("Error adding provider: ${error.code} - ${error.message}")
                    },
                    ifRight = { newProvider ->
                        // Add the new provider to the list, maintaining the Success state (E4.S8)
                        if (providers != null) {
                            _providersState.value = UiState.Success(providers + newProvider)
                        } else {
                            loadProviders() // Fallback to full reload if state wasn't Success
                        }
                        cancelDialog() // Hide form and reset
                    }
                )
        }
    }

    /**
     * Saves the general details (name, description, base URL, type) of an edited provider (E4.S10).
     * This does NOT update the API key.
     */
    private fun saveEditedProviderDetails(dialogState: ProvidersDialogState.EditProvider) {
        val providers = currentProviders ?: return
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

            providerApi.updateProvider(updatedProvider)
                .fold(
                    ifLeft = { error ->
                        updateProviderForm { it.withError("Error updating provider: ${error.message}") }
                        println("Error updating provider: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the provider in the list (E4.S10)
                        _providersState.value = UiState.Success(providers.map {
                            if (it.id == updatedProvider.id) updatedProvider else it
                        })
                        // Sync the selected provider instance if it's the one updated
                        if (_selectedProvider.value?.id == updatedProvider.id) {
                            _selectedProvider.value = updatedProvider
                        }
                        // Close the form after successful update
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Syncs the selected provider with the current list of providers.
     * If the selected provider is no longer in the list, clears the selection.
     */
    private fun syncSelectedProviderWithList(providers: List<LLMProvider>) {
        val currentSelection = _selectedProvider.value
        if (currentSelection != null && providers.none { it.id == currentSelection.id }) {
            // Selected provider is not in the new list, clear selection
            _selectedProvider.value = null
        }
    }
}
