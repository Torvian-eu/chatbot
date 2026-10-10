package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Resolves the complete effective compaction configuration of a single turn.
 *
 * It reads the preset driving the turn itself and validates it (existence, ownership, stored
 * threshold), then derives from it and the global preference whether a usable auxiliary configuration
 * exists and whether automatic compaction is enabled. It must be called inside the caller's active
 * transaction: the resolver has no transaction of its own and joins the caller's one, so the preset and
 * the preference describe one consistent snapshot. It is the single validator of the stored preference:
 * its structural and semantic problems are reported here, at zero query cost, as a
 * [ResolvedCompactionConfig.Unusable] carrying the reason and whether automatic compaction is enabled
 * for the turn. Whether the referenced model/settings can actually run a compaction is decided later,
 * lazily, when compaction becomes necessary.
 */
interface EffectiveCompactionConfigResolver {

    /**
     * Resolves the turn's effective configuration from the preset and the stored preference.
     *
     * Never raises: every failure is a value, because an unusable configuration only rejects a turn
     * whose automatic compaction is enabled and must still reach the manual path as a reason.
     *
     * @param userId Owner of the preset and of the global `conversation_compaction` preference row.
     * @param presetId Preset driving the turn, read here with ownership scoped to [userId]. Its
     *            threshold override replaces the preference's threshold and never affects whether the
     *            stored preference is accepted.
     * @return [ResolvedCompactionConfig.Usable] with the effective settings and whether automatic
     *         compaction is enabled for the turn, or [ResolvedCompactionConfig.Unusable] with the
     *         reason and the same flag. A missing or unowned preset always reports automatic
     *         compaction as disabled, because the preset flag is unknown.
     */
    suspend fun resolve(userId: Long, presetId: Long): ResolvedCompactionConfig
}
