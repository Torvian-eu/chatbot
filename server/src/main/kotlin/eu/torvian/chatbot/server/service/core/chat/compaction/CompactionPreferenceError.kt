package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures when writing or deleting the `conversation_compaction` preference.
 *
 * The write path validates only non-runtime concerns, so each failure category maps to a distinct
 * subtype: structural mistakes are [InvalidValue], denied READ access to the referenced rows is
 * [AccessDenied], a referenced row that no longer exists is [NotFound], and a structural mismatch
 * between the referenced model and settings is [IncompatibleConfiguration].
 */
sealed interface CompactionPreferenceError {

    /**
     * The preference value is malformed or structurally invalid (bad JSON, a missing or non-positive
     * id, blank instruction, non-positive threshold).
     *
     * @property reason Human-readable description of the invalid value.
     */
    data class InvalidValue(val reason: String) : CompactionPreferenceError

    /**
     * The owner has no READ access to the referenced model or settings, so the preference cannot be
     * stored for this user even though the references themselves are consistent.
     *
     * @property reason Human-readable description of the denied access.
     */
    data class AccessDenied(val reason: String) : CompactionPreferenceError

    /**
     * The preference references a settings row (or implicitly its model) that no longer exists.
     *
     * @property reason Human-readable description of the missing row.
     */
    data class NotFound(val reason: String) : CompactionPreferenceError

    /**
     * The preference is structurally valid and accessible but internally inconsistent: the referenced
     * settings belong to a different model than the referenced model id. This is the only remaining
     * write-path case that is a true incompatibility — runtime concerns (activity, streaming profile,
     * provider, strategy, credential) are validated later by the resolver when compaction runs.
     *
     * @property reason Human-readable description of the incompatible configuration.
     */
    data class IncompatibleConfiguration(val reason: String) : CompactionPreferenceError
}

/**
 * Maps a configuration-write failure to an API error for HTTP responses.
 *
 * Malformed values are client input problems (400 INVALID_ARGUMENT); denied access is a permission
 * problem (403 PERMISSION_DENIED); a missing referenced row is a not-found problem (404 NOT_FOUND);
 * an internally inconsistent model/settings pair is a model-configuration error
 * (400 MODEL_CONFIGURATION_ERROR).
 *
 * @receiver The configuration error to map.
 * @return The corresponding [ApiError].
 */
fun CompactionPreferenceError.toApiError(): ApiError = when (this) {
    is CompactionPreferenceError.InvalidValue ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)

    is CompactionPreferenceError.AccessDenied ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, reason)

    is CompactionPreferenceError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, reason)

    is CompactionPreferenceError.IncompatibleConfiguration ->
        apiError(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR, reason)
}
