package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.AddProviderRequest
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMProviderType
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
 * @property isAddingNewProvider UI state indicating if the new provider input form is visible (E4.S8).
 * @property newProviderForm The data for the "Add New Provider" form (E4.S8).
 * @property editingProvider The provider currently being edited (E4.S10, E4.S12). Null if no provider is being edited.
 * @property editingProviderForm The data for the "Edit Provider" form (E4.S10, E4.S12).
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

    private val _isAddingNewProvider = MutableStateFlow(false)

    /**
     * Controls the visibility of the "Add New Provider" form (E4.S8).
     */
    val isAddingNewProvider: StateFlow<Boolean> = _isAddingNewProvider.asStateFlow()

    private val _newProviderForm = MutableStateFlow(NewProviderFormState())

    /**
     * Holds the data for the "Add New Provider" form (E4.S8).
     */
    val newProviderForm: StateFlow<NewProviderFormState> = _newProviderForm.asStateFlow()

    private val _editingProvider = MutableStateFlow<LLMProvider?>(null)

    /**
     * The provider currently being edited (E4.S10, E4.S12). Null if no provider is being edited.
     */
    val editingProvider: StateFlow<LLMProvider?> = _editingProvider.asStateFlow()

    private val _editingProviderForm = MutableStateFlow(EditProviderFormState())

    /**
     * Holds the data for the "Edit Provider" form (E4.S10, E4.S12).
     */
    val editingProviderForm: StateFlow<EditProviderFormState> = _editingProviderForm.asStateFlow()

    private val _credentialUpdateLoading = MutableStateFlow(false)

    /**
     * Indicates if an API credential update operation is in progress (E4.S12).
     */
    val credentialUpdateLoading: StateFlow<Boolean> = _credentialUpdateLoading.asStateFlow()

    /**
     * Data class representing the state of the "Add New Provider" form.
     */
    data class NewProviderFormState(
        val name: String = "",
        val description: String = "",
        val baseUrl: String = "",
        val type: LLMProviderType = LLMProviderType.OPENAI, // Default to a common type
        val credential: String = "", // Raw API key input
        val errorMessage: String? = null // For inline validation/API errors
    )

    /**
     * Data class representing the state of the "Edit Provider" form.
     */
    data class EditProviderFormState(
        val name: String = "",
        val description: String = "",
        val baseUrl: String = "",
        val type: LLMProviderType = LLMProviderType.OPENAI,
        val newCredentialInput: String = "", // For updating the API key, not showing existing
        val errorMessage: String? = null // For inline validation/API errors
    )

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
        _isAddingNewProvider.value = true
        _newProviderForm.value = NewProviderFormState() // Reset form
    }

    /**
     * Cancels the new provider addition process.
     */
    fun cancelAddingNewProvider() {
        _isAddingNewProvider.value = false
        _newProviderForm.value = NewProviderFormState() // Clear form
    }

    /**
     * Updates the name field in the new provider form.
     */
    fun updateNewProviderName(name: String) {
        _newProviderForm.update { it.copy(name = name, errorMessage = null) }
    }

    /**
     * Updates the description field in the new provider form.
     */
    fun updateNewProviderDescription(description: String) {
        _newProviderForm.update { it.copy(description = description, errorMessage = null) }
    }

    /**
     * Updates the base URL field in the new provider form.
     */
    fun updateNewProviderBaseUrl(baseUrl: String) {
        _newProviderForm.update { it.copy(baseUrl = baseUrl, errorMessage = null) }
    }

    /**
     * Updates the type field in the new provider form.
     */
    fun updateNewProviderType(type: LLMProviderType) {
        _newProviderForm.update { it.copy(type = type, errorMessage = null) }
    }

    /**
     * Updates the credential field in the new provider form.
     */
    fun updateNewProviderCredential(credential: String) {
        _newProviderForm.update { it.copy(credential = credential, errorMessage = null) }
    }

    /**
     * Saves the new LLM provider configuration to the backend (E4.S8).
     */
    fun addNewProvider() {
        val form = _newProviderForm.value
        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            _newProviderForm.update { it.copy(errorMessage = "Name and Base URL cannot be empty.") }
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
                        _newProviderForm.update { it.copy(errorMessage = "Error adding provider: ${error.message}") }
                        println("Error adding provider: ${error.code} - ${error.message}")
                    },
                    ifRight = { newProvider ->
                        // Add the new provider to the list, maintaining the Success state (E4.S8)
                        if (currentProviders != null) {
                            _providersState.value = UiState.Success(currentProviders + newProvider)
                        } else {
                            loadProviders() // Fallback to full reload if state wasn't Success
                        }
                        cancelAddingNewProvider() // Hide form and reset
                    }
                )
        }
    }

    /**
     * Initiates the editing process for an existing provider (E4.S10).
     *
     * @param provider The [LLMProvider] to be edited.
     */
    fun startEditingProvider(provider: LLMProvider) {
        _editingProvider.value = provider
        _editingProviderForm.value = EditProviderFormState(
            name = provider.name,
            description = provider.description,
            baseUrl = provider.baseUrl,
            type = provider.type,
            newCredentialInput = "" // Credential input is always fresh
        )
    }

    /**
     * Cancels the editing process for a provider.
     */
    fun cancelEditingProvider() {
        _editingProvider.value = null
        _editingProviderForm.value = EditProviderFormState() // Clear form
    }

    /**
     * Updates the name field in the edit provider form.
     */
    fun updateEditingProviderName(name: String) {
        _editingProviderForm.update { it.copy(name = name, errorMessage = null) }
    }

    /**
     * Updates the description field in the edit provider form.
     */
    fun updateEditingProviderDescription(description: String) {
        _editingProviderForm.update { it.copy(description = description, errorMessage = null) }
    }

    /**
     * Updates the base URL field in the edit provider form.
     */
    fun updateEditingProviderBaseUrl(baseUrl: String) {
        _editingProviderForm.update { it.copy(baseUrl = baseUrl, errorMessage = null) }
    }

    /**
     * Updates the type field in the edit provider form.
     */
    fun updateEditingProviderType(type: LLMProviderType) {
        _editingProviderForm.update { it.copy(type = type, errorMessage = null) }
    }

    /**
     * Updates the new credential input field in the edit provider form.
     */
    fun updateEditingProviderNewCredentialInput(credential: String) {
        _editingProviderForm.update { it.copy(newCredentialInput = credential, errorMessage = null) }
    }

    /**
     * Saves the general details (name, description, base URL, type) of an edited provider (E4.S10).
     * This does NOT update the API key.
     */
    fun saveEditedProviderDetails() {
        val originalProvider = _editingProvider.value ?: return
        val form = _editingProviderForm.value

        if (form.name.isBlank() || form.baseUrl.isBlank()) {
            _editingProviderForm.update { it.copy(errorMessage = "Name and Base URL cannot be empty.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val updatedProvider = originalProvider.copy(
                name = form.name.trim(),
                description = form.description.trim(),
                baseUrl = form.baseUrl.trim(),
                type = form.type
                // apiKeyId is not updated here, it's immutable from the DTO perspective for general updates
            )
            val currentProviders = _providersState.value.dataOrNull

            providerApi.updateProvider(updatedProvider)
                .fold(
                    ifLeft = { error ->
                        _editingProviderForm.update { it.copy(errorMessage = "Error updating provider: ${error.message}") }
                        println("Error updating provider: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the provider in the list (E4.S10)
                        if (currentProviders != null) {
                            _providersState.value = UiState.Success(currentProviders.map {
                                if (it.id == updatedProvider.id) updatedProvider else it
                            })
                        } else {
                            loadProviders() // Fallback to full reload
                        }
                        // Keep editing state active, but clear error messages related to this save
                        _editingProviderForm.update { it.copy(errorMessage = null) }
                        // Also update the _editingProvider itself to reflect changes in the UI
                        _editingProvider.value = updatedProvider
                    }
                )
        }
    }

    /**
     * Updates the API key credential for the currently edited provider (E4.S12).
     * This is a separate action to handle the sensitive nature of credentials.
     */
    fun updateProviderCredential() {
        val providerId = _editingProvider.value?.id ?: return
        // Take non-blank credential, otherwise send null to remove it
        val credential = _editingProviderForm.value.newCredentialInput.takeIf { it.isNotBlank() }

        viewModelScope.launch(uiDispatcher) {
            _credentialUpdateLoading.value = true // Show loading indicator specifically for credential
            _editingProviderForm.update { it.copy(errorMessage = null) } // Clear previous form error

            providerApi.updateProviderCredential(providerId, UpdateProviderCredentialRequest(credential))
                .fold(
                    ifLeft = { error ->
                        _editingProviderForm.update { it.copy(errorMessage = "Error updating credential: ${error.message}") }
                        println("Error updating provider credential: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Credential updated successfully. Refresh the provider in the list to reflect apiKeyId status.
                        // We fetch just the updated provider to avoid a full list refresh for efficiency.
                        providerApi.getProviderById(providerId)
                            .fold(
                                ifLeft = { error ->
                                    println("Error refreshing provider after credential update: ${error.code} - ${error.message}. Reloading all providers as fallback.")
                                    loadProviders() // Fallback to full reload if fetching single fails
                                },
                                ifRight = { updatedProviderFromApi ->
                                    val currentProviders = _providersState.value.dataOrNull
                                    if (currentProviders != null) {
                                        _providersState.value = UiState.Success(currentProviders.map {
                                            if (it.id == updatedProviderFromApi.id) updatedProviderFromApi else it
                                        })
                                    } else {
                                        loadProviders() // Fallback to full reload if initial state was not success
                                    }
                                    _editingProvider.value =
                                        updatedProviderFromApi // Update the currently edited provider's details
                                }
                            )
                        _editingProviderForm.update {
                            it.copy(
                                newCredentialInput = "",
                                errorMessage = null
                            )
                        } // Clear input field
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
                // Optionally trigger a reload or show a generic error.
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
                        // This message should ideally be displayed via a transient UI channel (e.g., toast, SnackBar)
                        // rather than altering the main UiState directly for a list.
                        println(errorMessage) // For now, logging to console
                    },
                    ifRight = {
                        // Remove the deleted provider from the list (E4.S11)
                        _providersState.value = UiState.Success(currentProviders.filter { it.id != providerId })
                        // If the deleted provider was being edited, clear the editing state
                        if (_editingProvider.value?.id == providerId) {
                            cancelEditingProvider()
                        }
                    }
                )
        }
    }
}