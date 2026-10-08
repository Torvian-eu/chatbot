package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.server.service.core.LLMConfig

/**
 * Resolves the auxiliary compaction configuration for one enabled turn.
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
     * Resolves the turn's effective compaction settings into a usable auxiliary [LLMConfig].
     *
     * @param userId Owner whose READ access to the model/settings/provider is required.
     * @param settings The turn's effective compaction settings. Their ids are already validated as
     *            positive during turn preparation, so the failure modes left here are the ones that can
     *            appear after the preference was stored (missing/inactive rows, pairing, provider,
     *            credential). The runtime path reaches this resolver only when compaction is required,
     *            so such a failure surfaces exactly then, never while the thread fits.
     * @return Either a [ConversationCompactionError.InvalidConfiguration] when the configuration is
     *         not usable (existence, activity, pairing, credential), or an auxiliary [LLMConfig]
     *         with `tools = null` and `systemMessage` set to the settings' optional system prompt
     *         (empty when there is none). The instruction is not part of the config: the service
     *         appends it as the final user message of the auxiliary request and persists it as chunk
     *         provenance. READ access is not checked here (it is validated at write time by the
     *         configuration service).
     */
    suspend fun resolveAuxiliaryConfig(
        userId: Long,
        settings: EffectiveCompactionSettings
    ): Either<ConversationCompactionError, LLMConfig>
}
