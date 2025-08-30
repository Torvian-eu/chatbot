package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
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
 * @property isEditingProvider UI state indicating if a provider form is visible and in what mode.
 * @property providerForm The unified data for both "Add New Provider" and "Edit Provider" forms.
 * @property credentialUpdateLoading UI state indicating if an API credential update operation is in progress (E4.S12).
 */
class ProviderConfigViewModel(
    private val providerApi: ProviderApi,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Observable State for Compose UI ---

    private val _providersState = MutableStateFlow<UiState<ApiError, List<LLMProvider>>>(UiState.Idle)

    /**
     * The state of the list of all configured LLM providers (E4.S9).
     * Includes loading, success with data, or error states.
     */
    val providersState: StateFlow<UiState<ApiError, List<LLMProvider>>> = _providersState.asStateFlow()

    private val _isEditingProvider = MutableStateFlow(false)

    /**
     * Controls the visibility of the provider form.
     */
    val isEditingProvider: StateFlow<Boolean> = _isEditingProvider.asStateFlow()

    private val _editingProvider = MutableStateFlow<LLMProvider?>(null)

    /**
     * The provider currently being edited, if any.
     */
    val editingProvider: StateFlow<LLMProvider?> = _editingProvider.asStateFlow()

    private val _providerForm = MutableStateFlow(ProviderFormState())

    /**
     * Holds the unified data for both "Add New Provider" and "Edit Provider" forms.
     */
    val providerForm: StateFlow<ProviderFormState> = _providerForm.asStateFlow()

    private val _credentialUpdateLoading = MutableStateFlow(false)

    /**
     * Indicates if an API credential update operation is in progress (E4.S12).
     */
    val credentialUpdateLoading: StateFlow<Boolean> = _credentialUpdateLoading.asStateFlow()

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
                    }
                )
        }
    }

    /**
     * Initiates the process of adding a new provider by showing the form (E4.S8).
     */
    fun startAddingNewProvider() {
        _isEditingProvider.value = true
        _providerForm.value = ProviderFormState(mode = FormMode.NEW)
    }

    /**
     * Initiates the editing process for an existing provider (E4.S10).
     *
     * @param provider The [LLMProvider] to be edited.
     */
    fun startEditingProvider(provider: LLMProvider) {
        _isEditingProvider.value = true
        _providerForm.value = ProviderFormState(
            mode = FormMode.EDIT,
            name = provider.name,
            description = provider.description,
            baseUrl = provider.baseUrl,
            type = provider.type,
            credential = "" // Credential input is always fresh
        )
        _editingProvider.value = provider
    }

    /**
     * Cancels the provider form (both new and edit).
     */
    fun cancelProviderForm() {
        _isEditingProvider.value = false
        _providerForm.value = ProviderFormState() // Clear form
        _editingProvider.value = null
    }

    /**
     * Updates the provider form using a transformation function.
     */
    fun updateProviderForm(update: (ProviderFormState) -> ProviderFormState) {
        _providerForm.update { form -> update(form.copy(errorMessage = null)) }
    }

    /**
     * Saves the provider form data. Behavior depends on the form mode:
     * - NEW: Creates a new provider
     * - EDIT: Updates provider details (not credentials)
     */
    fun saveProviderForm() {
        val form = _providerForm.value
        when (form.mode) {
            FormMode.NEW -> addNewProvider(form)
            FormMode.EDIT -> saveEditedProviderDetails(form)
        }
    }

    /**
     * Saves the new LLM provider configuration to the backend (E4.S8).
     */
    private fun addNewProvider(form: ProviderFormState) {
        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            _providerForm.update { it.withError("Name and Base URL cannot be empty.") }
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
            val currentProviders = _providersState.value.dataOrNull

            providerApi.addProvider(request)
                .fold(
                    ifLeft = { error ->
                        _providerForm.update { it.withError("Error adding provider: ${error.message}") }
                        println("Error adding provider: ${error.code} - ${error.message}")
                    },
                    ifRight = { newProvider ->
                        // Add the new provider to the list, maintaining the Success state (E4.S8)
                        if (currentProviders != null) {
                            _providersState.value = UiState.Success(currentProviders + newProvider)
                        } else {
                            loadProviders() // Fallback to full reload if state wasn't Success
                        }
                        cancelProviderForm() // Hide form and reset
                    }
                )
        }
    }

    /**
     * Saves the general details (name, description, base URL, type) of an edited provider (E4.S10).
     * This does NOT update the API key.
     */
    private fun saveEditedProviderDetails(form: ProviderFormState) {
        val currentProviders = _providersState.value.dataOrNull ?: return
        val originalProvider = _editingProvider.value ?: return // Use the tracked editing provider

        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            _providerForm.update { it.withError("Name and Base URL cannot be empty.") }
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
                        _providerForm.update { it.withError("Error updating provider: ${error.message}") }
                        println("Error updating provider: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the provider in the list (E4.S10)
                        _providersState.value = UiState.Success(currentProviders.map {
                            if (it.id == updatedProvider.id) updatedProvider else it
                        })
                        // Close the form after successful update
                        cancelProviderForm()
                    }
                )
        }
    }

    /**
     * Updates the API key credential for the currently edited provider (E4.S12).
     * This is a separate action to handle the sensitive nature of credentials.
     */
    fun updateProviderCredential() {
        val form = _providerForm.value
        if (form.mode != FormMode.EDIT) return

        val originalProvider = _editingProvider.value ?: return // Use the tracked editing provider
        val currentProviders = _providersState.value.dataOrNull ?: return

        // Take non-blank credential, otherwise send null to remove it
        val credential = form.credential.takeIf { it.isNotBlank() }

        viewModelScope.launch(uiDispatcher) {
            _credentialUpdateLoading.value = true // Show loading indicator specifically for credential
            _providerForm.update { it.copy(errorMessage = null) } // Clear previous form error

            providerApi.updateProviderCredential(originalProvider.id, UpdateProviderCredentialRequest(credential))
                .fold(
                    ifLeft = { error ->
                        _providerForm.update { it.withError("Error updating credential: ${error.message}") }
                        println("Error updating provider credential: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Credential updated successfully. Refresh the provider in the list to reflect apiKeyId status.
                        providerApi.getProviderById(originalProvider.id)
                            .fold(
                                ifLeft = { error ->
                                    println("Error refreshing provider after credential update: ${error.code} - ${error.message}. Reloading all providers as fallback.")
                                    loadProviders() // Fallback to full reload if fetching single fails
                                },
                                ifRight = { updatedProviderFromApi ->
                                    _providersState.value = UiState.Success(currentProviders.map {
                                        if (it.id == updatedProviderFromApi.id) updatedProviderFromApi else it
                                    })
                                    // Update the editing provider reference as well
                                    _editingProvider.value = updatedProviderFromApi
                                }
                            )
                        _providerForm.update { it.copy(credential = "", errorMessage = null) } // Clear input field
                    }
                )
            _credentialUpdateLoading.value = false
        }
    }

    /**
     * Deletes a specific LLM provider configuration (E4.S11).
     *
     * @param providerId The ID of the provider to delete.
     */
    fun deleteProvider(providerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            val currentProviders = _providersState.value.dataOrNull
            if (currentProviders == null) {
                println("Cannot delete provider: provider list is not in Success state.")
                return@launch
            }

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
                        _providersState.value = UiState.Success(currentProviders.filter { it.id != providerId })
                        // If the deleted provider was being edited, clear the editing state
                        if (_editingProvider.value?.id == providerId) {
                            cancelProviderForm()
                        }
                    }
                )
        }
    }
}
