package eu.torvian.chatbot.server.service.core.error.preferences

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface for logical preference-management failures.
 */
sealed interface PreferenceError {
    /**
     * Preference input was malformed or violated a domain rule.
     *
     * @property reason Human-readable description of the invalid input.
     */
    data class InvalidInput(val reason: String) : PreferenceError

    /**
     * The caller attempted to write a device-scoped preference for a device that is not registered.
     *
     * @property clientDeviceId The client-side device identifier when it was provided.
     */
    data class DeviceNotRegistered(val clientDeviceId: String?) : PreferenceError
}

/**
 * Maps preference errors to API errors for HTTP responses.
 */
fun PreferenceError.toApiError(): ApiError = when (this) {
    is PreferenceError.InvalidInput ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)

    is PreferenceError.DeviceNotRegistered ->
        clientDeviceId?.let {
            apiError(CommonApiErrorCodes.NOT_FOUND, "Device is not registered", "clientDeviceId" to it)
        } ?: apiError(CommonApiErrorCodes.NOT_FOUND, "Device is not registered")
}

