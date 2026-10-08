package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either

/**
 * Resolves the complete effective compaction configuration of a single turn.
 *
 * It reads the preset driving the turn itself and validates it (existence, ownership, stored
 * threshold), then derives the configuration from it and the global preference. It must be called
 * inside the caller's active transaction: the resolver has no transaction of its own and joins the
 * caller's one, so the preset and the preference describe one consistent snapshot. It is the single
 * validator of the stored preference: its structural and semantic problems are reported here, at zero
 * query cost. Whether the referenced model/settings can actually run a compaction is decided later,
 * lazily, when compaction becomes necessary.
 */
interface EffectiveCompactionConfigResolver {

    /**
     * Resolves the turn's effective configuration from the preset and the stored preference.
     *
     * @param userId Owner of the preset and of the global `conversation_compaction` preference row.
     * @param presetId Preset driving the turn, read here with ownership scoped to [userId]: a missing
     *            or unowned row, or a stored threshold below `1`, rejects the turn. Its threshold
     *            override replaces the preference's threshold and never affects whether the stored
     *            preference is accepted.
     * @return Either a [ConversationCompactionError.InvalidConfiguration] when the preset does not exist
     *         for [userId], carries a stored threshold below `1`, or when a preference row exists but
     *         cannot be decoded or fails validation, or the resolved configuration.
     */
    suspend fun resolve(
        userId: Long,
        presetId: Long
    ): Either<ConversationCompactionError.InvalidConfiguration, ResolvedCompactionConfig>
}
