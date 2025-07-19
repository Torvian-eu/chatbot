package eu.torvian.chatbot.app.viewmodel

import eu.torvian.chatbot.common.api.ApiError

/**
 * Represents the different possible states of UI data loading and availability,
 * including an explicit Idle state.
 *
 * Used within UI State management classes to expose observable data to Compose UI.
 *
 * @param E The type of error that can occur (e.g., [ApiError]).
 * @param T The type of data that is loaded successfully.
 */
sealed class UiState<out E, out T> {

    /**
     * The initial state, representing that no data loading has started or that
     * there is currently no data selected/available (e.g., no session loaded).
     */
    object Idle : UiState<Nothing, Nothing>()

    /**
     * The state when data is currently being fetched or processed.
     */
    object Loading : UiState<Nothing, Nothing>()

    /**
     * Represents a successful state with the loaded data.
     * @property data The successfully loaded data.
     */
    data class Success<out T>(val data: T) : UiState<Nothing, T>()

    /**
     * Represents an error state.
     * @property error The error information.
     */
    data class Error<out E>(val error: E) : UiState<E, Nothing>()

    // --- Helper functions ---

    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isIdle: Boolean get() = this is Idle

    val dataOrNull: T?
        get() = (this as? Success)?.data

    val errorOrNull: E?
        get() = (this as? Error)?.error
}