package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * Consolidated state for all dialog management in the WorkersTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class WorkersDialogState {
    object None : WorkersDialogState()

    data class EditWorker(
        val worker: WorkerDto,
        val formState: WorkersFormState = WorkersFormState.fromWorker(worker)
    ) : WorkersDialogState()

    data class DeleteWorker(val worker: WorkerDto) : WorkersDialogState()
}

/**
 * Form state for editing a worker.
 *
 * @property displayName The display name of the worker.
 * @property allowedScopes Comma-separated list of allowed scopes.
 * @property error Error message if validation fails.
 */
data class WorkersFormState(
    val displayName: String = "",
    val allowedScopes: String = "",
    val error: String? = null
) {
    companion object {
        /**
         * Creates a form state from a WorkerDto.
         */
        fun fromWorker(worker: WorkerDto): WorkersFormState = WorkersFormState(
            displayName = worker.displayName,
            allowedScopes = worker.allowedScopes.joinToString(", ")
        )
    }

    /**
     * Validates the form and returns an error message if invalid.
     */
    fun validate(): String? = when {
        displayName.isBlank() -> "Display name cannot be empty"
        else -> null
    }

    /**
     * Returns a copy of this form state with the error cleared.
     */
    fun clearError(): WorkersFormState = copy(error = null)

    /**
     * Returns a copy of this form state with the given error set.
     */
    fun withError(message: String?): WorkersFormState = copy(error = message)
}
