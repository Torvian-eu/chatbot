package eu.torvian.chatbot.server.service.core.error.preset

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when retrieving a model preset.
 */
sealed interface ModelPresetError {

    /**
     * The requested model preset was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign preset identifier.
     */
    data class NotFound(val id: Long) : ModelPresetError
}

/**
 * Converts a [ModelPresetError] to its [ApiError] representation.
 *
 * A foreign preset is reported identically to a nonexistent one, so ownership never leaks through the
 * error surface.
 */
fun ModelPresetError.toApiError(): ApiError = when (this) {
    is ModelPresetError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Model preset not found", "presetId" to id.toString())
}
