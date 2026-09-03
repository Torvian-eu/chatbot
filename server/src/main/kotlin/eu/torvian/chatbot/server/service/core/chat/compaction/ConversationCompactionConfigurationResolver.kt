package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.LLMConfig

/**
 * Resolves the auxiliary compaction configuration for a user's preference.
 *
 * This is the runtime fast path: it validates that the referenced rows exist, are active and
 * mutually consistent, and resolve a usable credential — but it does **not** re-check READ access
 * or the chat-like/non-streaming settings profile (both are static write-time concerns enforced by
 * the configuration service). Correctness and access are enforced explicitly by the configuration
 * service when the preference is stored; at runtime the compaction path resolves as quickly as
 * possible.
 */
interface ConversationCompactionConfigurationResolver {

    /**
     * Resolves the preference into a usable auxiliary [LLMConfig].
     *
     * @param userId Owner whose READ access to the model/settings/provider is required.
     * @param preference The decoded compaction preference. A `null` [ConversationCompactionPreference.modelId]
     *            or [ConversationCompactionPreference.settingsId] (referenced rows no longer exist)
     *            fails with [ConversationCompactionError.InvalidConfiguration]; the runtime path
     *            reaches this resolver only when compaction is required, so such a failure surfaces
     *            exactly then, never while the thread fits.
     * @return Either a [ConversationCompactionError.InvalidConfiguration] when the configuration is
     *         not usable (existence, activity, pairing, credential), or an auxiliary [LLMConfig]
     *         with `tools = null` and `systemMessage` set to the preference's [ConversationCompactionPreference.systemMessage]
     *         (empty when the preference has none). The preference instruction is not part of the
     *         config: the service appends it as the final user message of the auxiliary request and
     *         persists it as chunk provenance. READ access is not checked here (it is validated at
     *         write time by the configuration service).
     */
    suspend fun resolveAuxiliaryConfig(
        userId: Long,
        preference: ConversationCompactionPreference
    ): Either<ConversationCompactionError, LLMConfig>
}
