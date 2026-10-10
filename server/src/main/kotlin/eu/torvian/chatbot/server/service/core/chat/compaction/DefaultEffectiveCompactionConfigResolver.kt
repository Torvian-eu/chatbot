package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
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

    override suspend fun resolve(userId: Long, presetId: Long): ResolvedCompactionConfig {
        // The preset read is ownership-scoped, so a preset of another user is as unusable as a deleted
        // one; the caller already proved the preset exists, and this read is defense-in-depth against a
        // concurrent delete. A missing preset cannot enable compaction, because its flag is unknown.
        val preset = modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)).singleOrNull()
            ?: return ResolvedCompactionConfig.Unusable(
                reason = "Model preset $presetId does not exist for user $userId, or is not owned by them",
                automaticCompactionEnabled = false
            )

        val rawValue = userPreferenceDao
            .getGlobalPreference(userId, PreferenceKeys.CONVERSATION_COMPACTION)
            ?.prefValue

        // Decoded before the effective flag is computed: an undecodable row keeps the field's enabled
        // default, so a preset that does not disable automatic compaction still reports it as enabled.
        val decodedResult = rawValue?.let { value ->
            runCatching { json.decodeFromString<ConversationCompactionPreference>(value) }
        }
        val decoded = decodedResult?.getOrNull()

        // Absent row means automatic compaction is disabled (the DTO default describes a stored row
        // that omits the field, not a missing row), so an unconfigured user keeps today's behaviour: no automatic
        // compaction, no rejection.
        val automaticCompactionEnabled = preset.automaticCompactionEnabled &&
            decodedResult != null &&
            (decoded?.automaticCompactionEnabled ?: true)

        return if (decoded != null) {
            usableConfiguration(presetId, preset, decoded, automaticCompactionEnabled)
        } else {
            ResolvedCompactionConfig.Unusable(
                reason = unusableReason(userId, presetId, preset, rawValue, decodedResult?.exceptionOrNull()),
                automaticCompactionEnabled = automaticCompactionEnabled
            )
        }
    }

    /**
     * Builds the resolved configuration of a decoded preference, or the reason it cannot be used.
     *
     * Every unusable outcome carries the turn's effective automatic-compaction flag, so the caller that
     * decides whether to reject the runtime reads one fact from one place.
     *
     * @param presetId Preset driving the turn, used in the reason text.
     * @param preset The resolved preset row, contributing the optional threshold override.
     * @param decoded The successfully decoded preference.
     * @param automaticCompactionEnabled Whether automatic compaction is enabled for the turn,
     *            carried by the resolved configuration on success and reported on the negative variant
     *            otherwise.
     * @return A [ResolvedCompactionConfig.Usable] configuration, or an explanatory
     *         [ResolvedCompactionConfig.Unusable].
     */
    private fun usableConfiguration(
        presetId: Long,
        preset: ModelPresetEntity,
        decoded: ConversationCompactionPreference,
        automaticCompactionEnabled: Boolean
    ): ResolvedCompactionConfig {
        val presetThresholdTokens = preset.compactionThresholdTokens
        // A stored threshold below 1 is only reachable through a hand-edited row (both write paths
        // reject it), and the compaction runtime requires a positive threshold.
        if (presetThresholdTokens != null && presetThresholdTokens < 1L) {
            return ResolvedCompactionConfig.Unusable(
                reason = "Model preset $presetId has an invalid compaction threshold $presetThresholdTokens: " +
                    "it must be at least 1, or unset to use the user preference threshold",
                automaticCompactionEnabled = automaticCompactionEnabled
            )
        }

        // The stored preference is validated here, once, with pure value checks that need no query: a
        // half-configured row cannot be compacted at all, so it is reported as unusable instead of
        // failing mid-turn when the thread finally exceeds the threshold. These checks judge the STORED
        // value only: the preset's threshold override changes the threshold that is used, never whether
        // the stored configuration is accepted.
        val modelId = decoded.modelId
            ?: return unusable(
                reason = "Compaction modelId is not set",
                automaticCompactionEnabled = automaticCompactionEnabled
            )
        val settingsId = decoded.settingsId
            ?: return unusable(
                reason = "Compaction settingsId is not set",
                automaticCompactionEnabled = automaticCompactionEnabled
            )
        if (modelId <= 0L) {
            return unusable("Compaction modelId must be positive", automaticCompactionEnabled)
        }
        if (settingsId <= 0L) {
            return unusable("Compaction settingsId must be positive", automaticCompactionEnabled)
        }
        if (decoded.instruction.isBlank()) {
            return unusable("Compaction instruction must not be blank", automaticCompactionEnabled)
        }
        if (decoded.thresholdTokens <= 0L) {
            return unusable("Compaction thresholdTokens must be positive", automaticCompactionEnabled)
        }

        return ResolvedCompactionConfig.Usable(
            settings = EffectiveCompactionSettings(
                modelId = modelId,
                settingsId = settingsId,
                instruction = decoded.instruction,
                systemMessage = decoded.systemMessage,
                summaryLabel = decoded.summaryLabel,
                // The preset override wins for the whole turn; a null override defers to the preference
                // (which itself defaults to 100_000).
                thresholdTokens = presetThresholdTokens ?: decoded.thresholdTokens
            ),
            automaticCompactionEnabled = automaticCompactionEnabled
        )
    }

    /**
     * Builds the negative resolution of a stored-preference validation failure.
     *
     * @param reason User-facing explanation of the invalid stored value.
     * @param automaticCompactionEnabled Whether automatic compaction is enabled for the turn, reported
     *            alongside the reason.
     * @return The [ResolvedCompactionConfig.Unusable] resolution for that failure.
     */
    private fun unusable(reason: String, automaticCompactionEnabled: Boolean): ResolvedCompactionConfig =
        ResolvedCompactionConfig.Unusable(
            reason = reason,
            automaticCompactionEnabled = automaticCompactionEnabled
        )

    /**
     * Explains why no configuration could be decoded.
     *
     * @param userId Owner of the preference, used in the reason text.
     * @param presetId Preset driving the turn, used in the reason text.
     * @param preset The resolved preset row, contributing the optional threshold override.
     * @param rawValue The raw stored preference value, or `null` when no row exists.
     * @param decodeFailure The decoding exception, or `null` when decoding succeeded.
     * @return The user-facing reason for the unusable configuration.
     */
    private fun unusableReason(
        userId: Long,
        presetId: Long,
        preset: ModelPresetEntity,
        rawValue: String?,
        decodeFailure: Throwable?
    ): String {
        val presetThresholdTokens = preset.compactionThresholdTokens
        if (presetThresholdTokens != null && presetThresholdTokens < 1L) {
            return "Model preset $presetId has an invalid compaction threshold " +
                "$presetThresholdTokens: it must be at least 1, or unset to use " +
                "the user preference threshold"
        }
        if (rawValue == null) {
            return "No conversation_compaction preference is stored for user $userId: " +
                "configure the compaction model and settings first"
        }
        // A structurally invalid preference is a hard configuration error, carrying the serialization
        // exception's own message for diagnostics: there is no partially-usable configuration (no
        // threshold hint), so nothing can be compacted and both paths report the same reason.
        return "The conversation_compaction preference is structurally invalid: " +
            (decodeFailure?.message ?: "malformed JSON")
    }
}
