package eu.torvian.chatbot.server.service.core.error.serverbuiltin

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Maps an [UpdateServerBuiltInToolNamePrefixError] to a standardized [ApiError].
 *
 * Invalid input becomes 400 `INVALID_ARGUMENT`; a rename failure is a server-side inconsistency
 * (the transaction rolls back) and surfaces as 500 `INTERNAL`.
 */
fun UpdateServerBuiltInToolNamePrefixError.toApiError(): ApiError = when (this) {
    is UpdateServerBuiltInToolNamePrefixError.InvalidInput ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)

    is UpdateServerBuiltInToolNamePrefixError.RenameFailed ->
        apiError(
            CommonApiErrorCodes.INTERNAL,
            "Failed to update the server built-in tool name prefix: renaming the user's tools failed"
        )
}
