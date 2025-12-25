package eu.torvian.chatbot.app.viewmodel.common

import eu.torvian.chatbot.app.domain.events.GenericAppError
import eu.torvian.chatbot.app.domain.events.GenericAppSuccess
import eu.torvian.chatbot.app.domain.events.GenericAppWarning
import eu.torvian.chatbot.app.domain.events.apiRequestError
import eu.torvian.chatbot.app.domain.events.repositoryAppError
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Service responsible for standardizing notification handling.
 * Encapsulates the logic for creating and emitting error, warning, and success events via the EventBus.
 */
class NotificationService(
    private val eventBus: EventBus
) {
    companion object {
        private val logger = kmpLogger<NotificationService>()
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

    /**
     * Handles a generic application error by creating a standardized error event and emitting it.
     * Used for business logic errors, validation errors, and other application-specific errors.
     *
     * @param shortMessage The short, user-friendly error message (can be internationalized)
     * @param detailedMessage Optional detailed technical error message in English for developers
     * @param originalThrowable Optional original exception that caused this error
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun genericError(
        shortMessage: String,
        detailedMessage: String? = null,
        originalThrowable: Throwable? = null,
        isRetryable: Boolean = false
    ): String {
        val message =
            shortMessage + (detailedMessage?.let { " - $it" } ?: "") + (originalThrowable?.let { " - ${it.message}" }
                ?: "")
        logger.error("Generic error: $message", originalThrowable)
        val errorEvent = GenericAppError(
            message = message,
            isRetryable = isRetryable
        )
        eventBus.emitEvent(errorEvent)
        return errorEvent.eventId
    }

    /**
     * Handles a generic application error by creating a standardized error event and emitting it.
     * Used for business logic errors, validation errors, and other application-specific errors.
     *
     * @param shortMessageRes The resource ID for the short, user-friendly error message
     * @param detailedMessage Optional detailed technical error message in English for developers
     * @param originalThrowable Optional original exception that caused this error
     * @param isRetryable Whether this error can be retried
     * @return The event ID of the emitted error event
     */
    suspend fun genericError(
        shortMessageRes: StringResource,
        detailedMessage: String? = null,
        originalThrowable: Throwable? = null,
        isRetryable: Boolean = false
    ): String {
        val shortMessage = getString(shortMessageRes)
        return genericError(shortMessage, detailedMessage, originalThrowable, isRetryable)
    }

    /**
     * Handles a generic application warning by creating a standardized warning event and emitting it.
     * Used for non-critical issues that don't require user action.
     *
     * @param shortMessage The short, user-friendly warning message (can be internationalized)
     * @param detailedMessage Optional detailed technical warning message in English for developers
     * @return The event ID of the emitted warning event
     */
    suspend fun genericWarning(
        shortMessage: String,
        detailedMessage: String? = null
    ): String {
        val message = shortMessage + detailedMessage?.let { " - $it" }
        logger.warn("Generic warning: $message")
        val warningEvent = GenericAppWarning(message)
        eventBus.emitEvent(warningEvent)
        return warningEvent.eventId
    }

    /**
     * Handles a generic application warning by creating a standardized warning event and emitting it.
     * Used for non-critical issues that don't require user action.
     *
     * @param shortMessageRes The resource ID for the short, user-friendly warning message
     * @param detailedMessage Optional detailed technical warning message in English for developers
     * @return The event ID of the emitted warning event
     */
    suspend fun genericWarning(
        shortMessageRes: StringResource,
        detailedMessage: String? = null
    ): String {
        val shortMessage = getString(shortMessageRes)
        return genericWarning(shortMessage, detailedMessage)
    }

    /**
     * Handles a generic application success by creating a standardized success event and emitting it.
     * Used for user feedback on successful operations.
     *
     * @param shortMessage The short, user-friendly success message (can be internationalized)
     * @return The event ID of the emitted success event
     */
    suspend fun genericSuccess(
        shortMessage: String
    ): String {
        logger.info("Generic success: $shortMessage")
        val successEvent = GenericAppSuccess(shortMessage)
        eventBus.emitEvent(successEvent)
        return successEvent.eventId
    }
}
