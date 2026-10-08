package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import kotlinx.serialization.json.Json

/**
 * Default [EffectiveCompactionConfigResolver] reading the preset and the global preference.
 *
 * @property modelPresetDao Reads the turn's preset, ownership-scoped to the calling user.
 * @property userPreferenceDao Reads the global preference row; device-scoped rows cannot enable
 *            compaction and are not consulted.
 * @property json Shared JSON codec used to decode the preference value.
 */
class DefaultEffectiveCompactionConfigResolver(
    private val modelPresetDao: ModelPresetDao,
    private val userPreferenceDao: UserPreferenceDao,
    private val json: Json
) : EffectiveCompactionConfigResolver {

    override suspend fun resolve(
        userId: Long,
        presetId: Long
    ): Either<ConversationCompactionError.InvalidConfiguration, ResolvedCompactionConfig> = either {
        // The preset read is ownership-scoped, so a preset of another user is as unusable as a deleted
        // one; the caller already proved the preset exists, and this read is defense-in-depth against a
        // concurrent delete.
        val preset = modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)).singleOrNull()
            ?: raise(
                ConversationCompactionError.InvalidConfiguration(
                    "Model preset $presetId does not exist for user $userId, or is not owned by them"
                )
            )

        // Both preset write paths reject a non-positive threshold, so only a hand-edited row can reach a
        // turn with one. Failing here keeps the compaction runtime's "stored threshold is positive"
        // invariant without an extra lookup or a silent zero threshold.
        val presetThresholdTokens = preset.compactionThresholdTokens
        if (presetThresholdTokens != null && presetThresholdTokens < 1L) {
            raise(
                ConversationCompactionError.InvalidConfiguration(
                    "Model preset $presetId has an invalid compaction threshold " +
                        "$presetThresholdTokens: it must be at least 1, or unset to use " +
                        "the user preference threshold"
                )
            )
        }

        // The preset check comes first so a preset-disabled turn never even reads or decodes the
        // preference: a stored preference that is malformed cannot fail a turn that cannot compact.
        if (!preset.compactionEnabled) {
            return@either ResolvedCompactionConfig.Disabled
        }

        val rawValue = userPreferenceDao.getGlobalPreference(userId, PreferenceKeys.CONVERSATION_COMPACTION)
            ?.prefValue
            ?: return@either ResolvedCompactionConfig.Disabled

        // A structurally invalid preference is a hard configuration error, carrying the serialization
        // exception's own message for diagnostics: there is no partially-usable configuration (no
        // threshold hint), so the turn is rejected before anything is persisted and before any counting
        // or compaction can run.
        val decoded = try {
            json.decodeFromString<ConversationCompactionPreference>(rawValue)
        } catch (e: Exception) {
            raise(
                ConversationCompactionError.InvalidConfiguration(
                    "The conversation_compaction preference is structurally invalid: " +
                        (e.message ?: "malformed JSON")
                )
            )
        }

        // A preference stored with `enabled = false` behaves exactly like an absent row: automatic
        // compaction is off for this turn.
        if (!decoded.enabled) {
            return@either ResolvedCompactionConfig.Disabled
        }

        // The stored preference is validated here, once, with pure value checks that need no query. A
        // half-configured row cannot be compacted at all, so it is rejected before anything is
        // persisted instead of failing mid-turn when the thread finally exceeds the threshold. These
        // checks judge the STORED value only: the preset's threshold override changes the threshold
        // that is used, never whether the stored configuration is accepted.
        val modelId = decoded.modelId
            ?: raise(ConversationCompactionError.InvalidConfiguration("Compaction modelId is not set"))
        val settingsId = decoded.settingsId
            ?: raise(ConversationCompactionError.InvalidConfiguration("Compaction settingsId is not set"))
        ensure(modelId > 0L) { ConversationCompactionError.InvalidConfiguration("Compaction modelId must be positive") }
        ensure(settingsId > 0L) { ConversationCompactionError.InvalidConfiguration("Compaction settingsId must be positive") }
        ensure(decoded.instruction.isNotBlank()) { ConversationCompactionError.InvalidConfiguration("Compaction instruction must not be blank") }
        ensure(decoded.thresholdTokens > 0L) { ConversationCompactionError.InvalidConfiguration("Compaction thresholdTokens must be positive") }

        ResolvedCompactionConfig.Enabled(
            settings = EffectiveCompactionSettings(
                modelId = modelId,
                settingsId = settingsId,
                instruction = decoded.instruction,
                systemMessage = decoded.systemMessage,
                summaryLabel = decoded.summaryLabel,
                // The preset override wins for the whole turn; a null override defers to the preference
                // (which itself defaults to 100_000).
                thresholdTokens = presetThresholdTokens ?: decoded.thresholdTokens
            )
        )
    }
}
