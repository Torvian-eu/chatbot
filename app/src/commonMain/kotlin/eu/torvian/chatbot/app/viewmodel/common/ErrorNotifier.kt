package eu.torvian.chatbot.app.viewmodel.common

import eu.torvian.chatbot.app.domain.events.apiRequestError
import eu.torvian.chatbot.app.domain.events.repositoryAppError
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Service responsible for standardizing error handling and notification.
 * Encapsulates the logic for creating and emitting error events via the EventBus.
 */
class ErrorNotifier(
    private val eventBus: EventBus
) {
    companion object {
        private val logger = kmpLogger<ErrorNotifier>()
    }

    /**
     * Handles an API error by creating a standardized error event and emitting it.
     *
     * @param error The API error that occurred
     * @param shortMessage The short error message string
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun apiError(
        error: ApiError,
        shortMessage: String,
        isRetryable: Boolean = false
    ): String {
        logger.error("API error: $error")
        val errorEvent = apiRequestError(
            apiError = error,
            shortMessage = shortMessage,
            isRetryable = isRetryable
        )
        eventBus.emitEvent(errorEvent)
        return errorEvent.eventId
    }

    /**
     * Handles an API error by creating a standardized error event and emitting it.
     *
     * @param error The API error that occurred
     * @param shortMessageRes The resource ID for the short error message
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun apiError(
        error: ApiError,
        shortMessageRes: StringResource,
        isRetryable: Boolean = false
    ): String {
        val shortMessage = getString(shortMessageRes)
        return apiError(error, shortMessage, isRetryable)
    }

    /**
     * Handles a repository error by creating a standardized error event and emitting it.
     *
     * @param error The repository error that occurred
     * @param shortMessage The short error message string
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun repositoryError(
        error: RepositoryError,
        shortMessage: String,
        isRetryable: Boolean = false
    ): String {
        logger.error("Repository error: $error")
        val errorEvent = repositoryAppError(
            repositoryError = error,
            shortMessage = shortMessage,
            isRetryable = isRetryable
        )
        eventBus.emitEvent(errorEvent)
        return errorEvent.eventId
    }

    /**
     * Handles a repository error by creating a standardized error event and emitting it.
     *
     * @param error The repository error that occurred
     * @param shortMessageRes The resource ID for the short error message
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun repositoryError(
        error: RepositoryError,
        shortMessageRes: StringResource,
        isRetryable: Boolean = false
    ): String {
        val shortMessage = getString(shortMessageRes)
        return repositoryError(error, shortMessage, isRetryable)
    }
}
