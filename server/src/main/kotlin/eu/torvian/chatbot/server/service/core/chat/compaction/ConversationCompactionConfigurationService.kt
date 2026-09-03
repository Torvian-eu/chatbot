package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either

/**
 * Validates and persists the global `conversation_compaction` preference.
 *
 * PUT of the well-known key is branched from the generic preference path so a malformed or
 * incompatible value is rejected before storage, while GET/DELETE retain the existing generic
 * surface. DELETE removes only the global row, which disables automatic compaction; a stored
 * preference with `enabled = false` disables runtime compaction without any error; a stored
 * preference with null (deleted) model/settings references likewise cannot compact and raises an
 * invalid-configuration error at runtime only when compaction is actually needed, while the
 * configuration is preserved for later re-configuration.
 */
interface ConversationCompactionConfigurationService {

    /**
     * Validates the raw JSON preference and stores its canonical JSON form in the GLOBAL scope.
     *
     * Structural validation always applies. When the preference references a model and settings, the
     * write path checks the **non-runtime** concerns only: READ access to the model and settings, that
     * the referenced settings exist and belong to the referenced model, and that the settings profile
     * is chat-like and non-streaming. Runtime concerns (model activity, provider, strategy,
     * credential) are validated only when compaction actually runs, by the runtime resolver — never
     * here. A null model/settings reference is stored as-is and fails at runtime only when compaction
     * is required.
     *
     * @param userId Owner of the preference.
     * @param rawValue The raw JSON string to validate and store.
     * @return Either a [ConversationCompactionConfigurationError] or Unit on success.
     */
    suspend fun updateConfiguration(
        userId: Long,
        rawValue: String
    ): Either<ConversationCompactionConfigurationError, Unit>

    /**
     * Deletes the user's global `conversation_compaction` preference row.
     *
     * Idempotent: when no row exists nothing happens. After deletion automatic compaction is
     * disabled for the user.
     *
     * @param userId Owner of the preference.
     * @return Either a [ConversationCompactionConfigurationError] or Unit on success.
     */
    suspend fun deleteConfiguration(
        userId: Long
    ): Either<ConversationCompactionConfigurationError, Unit>
}
