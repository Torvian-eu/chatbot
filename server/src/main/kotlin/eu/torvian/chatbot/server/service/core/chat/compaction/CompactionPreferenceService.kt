package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either

/**
 * Validates and persists the global `conversation_compaction` preference.
 *
 * PUT of the well-known key is branched from the generic preference path so a malformed or
 * incompatible value is rejected before storage, while GET/DELETE retain the existing generic
 * surface. DELETE removes only the global row, and a stored preference with
 * `automaticCompactionEnabled = false` also disables runtime compaction without any error, while the
 * stored configuration is preserved for later re-enabling. A null (deleted) model/settings reference
 * is legitimate only while that flag is false: an enabled preference must carry positive ids, a
 * non-blank instruction and a positive threshold, so the write path rejects a half-configured one
 * instead of storing a state that makes every turn of a compaction-enabled session fail.
 */
interface CompactionPreferenceService {

    /**
     * Validates the raw JSON preference and stores its canonical JSON form in the GLOBAL scope.
     *
     * Structural validation always applies. When the preference references a model and settings, the
     * write path checks the **non-runtime** concerns only: READ access to the model and settings, that
     * the referenced settings exist and belong to the referenced model, and that the settings profile
     * is chat-like and non-streaming. Runtime concerns (model activity, provider, strategy,
     * credential) are validated only when compaction actually runs, by the runtime resolver — never
     * here. A null model/settings reference is accepted only while the preference is disabled: an
     * enabled preference that omits either id is rejected as an invalid value, because it could never
     * resolve a compactor.
     *
     * @param userId Owner of the preference.
     * @param rawValue The raw JSON string to validate and store.
     * @return Either a [CompactionPreferenceError] or Unit on success.
     */
    suspend fun updateConfiguration(
        userId: Long,
        rawValue: String
    ): Either<CompactionPreferenceError, Unit>

    /**
     * Deletes the user's global `conversation_compaction` preference row.
     *
     * Idempotent: when no row exists nothing happens. After deletion automatic compaction is
     * disabled for the user.
     *
     * @param userId Owner of the preference.
     * @return Either a [CompactionPreferenceError] or Unit on success.
     */
    suspend fun deleteConfiguration(
        userId: Long
    ): Either<CompactionPreferenceError, Unit>
}
