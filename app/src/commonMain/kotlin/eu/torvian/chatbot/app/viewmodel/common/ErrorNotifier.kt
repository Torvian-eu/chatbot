package eu.torvian.chatbot.app.viewmodel.common

import eu.torvian.chatbot.app.domain.events.apiRequestError
import eu.torvian.chatbot.app.service.misc.EventBus
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
        val errorEvent = apiRequestError(
            apiError = error,
            shortMessage = shortMessage,
            isRetryable = isRetryable
        )
        eventBus.emitEvent(errorEvent)
        return errorEvent.eventId
    }
    
    /**
     * Handles an API error with a custom short message string.
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
        val errorEvent = apiRequestError(
            apiError = error,
            shortMessage = shortMessage,
            isRetryable = isRetryable
        )
        eventBus.emitEvent(errorEvent)
        return errorEvent.eventId
    }
}
