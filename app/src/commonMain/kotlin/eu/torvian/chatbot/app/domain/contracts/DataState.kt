package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.app.repository.RepositoryError

/**
 * Represents the different possible states of data loading and availability for the repository layer.
 *
 * Used within Repository classes to expose observable data streams to ViewModels through StateFlow.
 * This provides a unified way to handle the complete lifecycle of data resources including
 * idle, loading, success, and error states.
 *
 * @param E The type of error that can occur (specifically [RepositoryError] for repository operations).
 * @param T The type of data that is loaded successfully.
 */
sealed class DataState<out E, out T> {

    /**
     * The initial state, representing that no data loading has started or that
     * there is currently no data available. This is the default state before
     * any repository operations are initiated.
     */
    object Idle : DataState<Nothing, Nothing>()

    /**
     * The state when data is currently being fetched, processed, or updated.
     * This indicates that a repository operation is in progress.
     */
    object Loading : DataState<Nothing, Nothing>()

    /**
     * Represents a successful state with the loaded data.
     * This indicates that the repository operation completed successfully
     * and the data is available for consumption.
     *
     * @property data The successfully loaded data.
     */
    data class Success<out T>(val data: T) : DataState<Nothing, T>()

    /**
     * Represents an error state that occurred during a repository operation.
     * This includes network errors, server errors, serialization errors,
     * and other unexpected failures.
     *
     * @property error The error information, typically a [RepositoryError].
     */
    data class Error<out E>(val error: E) : DataState<E, Nothing>()

    // --- Helper functions for state checking ---

    /**
     * Returns true if this state represents a loading operation in progress.
     */
    val isLoading: Boolean get() = this is Loading

    /**
     * Returns true if this state represents a successful operation with data.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns true if this state represents an error condition.
     */
    val isError: Boolean get() = this is Error

    /**
     * Returns true if this state represents the initial idle state.
     */
    val isIdle: Boolean get() = this is Idle

    /**
     * Returns the data if this is a Success state, null otherwise.
     * Useful for safe data extraction without explicit type checking.
     */
    val dataOrNull: T?
        get() = (this as? Success)?.data

    /**
     * Returns the error if this is an Error state, null otherwise.
     * Useful for safe error extraction without explicit type checking.
     */
    val errorOrNull: E?
        get() = (this as? Error)?.error
}
