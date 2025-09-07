package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ProvidersDialogState
import eu.torvian.chatbot.app.repository.ProviderRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.AddProviderRequest
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.UpdateProviderCredentialRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * - Communicating with the backend via [ProviderRepository].
 *
 * @constructor
 * @param providerRepository The repository for provider-related operations.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property providersState The state of the list of all configured LLM providers (E4.S9).
 * @property selectedProvider The currently selected provider in the master-detail UI.
 * @property dialogState The current dialog state for the providers tab.
 *
 * TODO: send error messages to UI, using EventBus.
 */
class ProviderConfigViewModel(
    private val providerRepository: ProviderRepository,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val userSelectedProviderId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<ProvidersDialogState>(ProvidersDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all configured LLM providers (E4.S9).
     * Includes loading, success with data, or error states.
     */
    val providersState: StateFlow<DataState<RepositoryError, List<LLMProvider>>> = providerRepository.providers

    /**
     * The currently selected provider in the master-detail UI.
     */
    val selectedProvider: StateFlow<LLMProvider?> = combine(
        providersState.map { it.dataOrNull },
        userSelectedProviderId
    ) { providersList, currentSelectedId ->
        providersList?.find { it.id == currentSelectedId }
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
            providerRepository.loadProviders()
                .mapLeft { error ->
                    println("Error loading providers: ${error.message}")
                }

        }
    }

    /**
     * Selects a provider (or clears selection when null).
     */
    fun selectProvider(provider: LLMProvider?) {
        userSelectedProviderId.value = provider?.id
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

            providerRepository.updateProviderCredential(provider.id, UpdateProviderCredentialRequest(credential))
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
                        println("Error updating provider credential: ${error.message}")
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
     * Deletes a specific LLM provider configuration (E4.S11).
     *
     * @param providerId The ID of the provider to delete.
     */
    fun deleteProvider(providerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            providerRepository.deleteProvider(providerId)
                .fold(
                    ifLeft = { error ->
                        println(error.message)
                    },
                    ifRight = {
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
            providerRepository.addProvider(request)
                .fold(
                    ifLeft = { error ->
                        updateProviderForm { it.withError("Error adding provider: ${error.message}") }
                        println("Error adding provider: ${error.message}")
                    },
                    ifRight = { newProvider ->
                        cancelDialog()
                        selectProvider(newProvider)
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
                        println("Error updating provider: ${error.message}")
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }
}
