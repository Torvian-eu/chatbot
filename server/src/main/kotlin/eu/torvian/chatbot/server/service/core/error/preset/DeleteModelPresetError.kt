package eu.torvian.chatbot.server.service.core.error.preset

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when deleting a model preset.
 *
 * Deleting a preset is non-destructive for agent roles: their `model_preset_id` is nulled
 * (`ON DELETE SET NULL`), so the roles survive and merely become preset-less and therefore
 * non-sendable. There is no "preset in use" rejection.
 */
sealed interface DeleteModelPresetError {

    /**
     * The model preset to delete was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign preset identifier.
     */
    data class NotFound(val id: Long) : DeleteModelPresetError
}

/**
 * Converts a [DeleteModelPresetError] to its [ApiError] representation.
 *
 * A foreign preset is reported identically to a nonexistent one, so ownership never leaks through the
 * error surface.
 */
fun DeleteModelPresetError.toApiError(): ApiError = when (this) {
    is DeleteModelPresetError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Model preset not found", "presetId" to id.toString())
}
