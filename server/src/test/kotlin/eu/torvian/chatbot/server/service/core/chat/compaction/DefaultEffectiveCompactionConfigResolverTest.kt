package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the resolution matrix of the effective per-turn compaction configuration: the preset read
 * and its validation, the preset short-circuit, the preference read and decode, the `enabled` flags,
 * the five zero-query validation checks of the stored value and the threshold precedence.
 *
 * The JSON codec is real (only the DAOs are mocked), so the decode rules that decide between a disabled
 * turn and a rejected one are exercised as they run in production.
 */
class DefaultEffectiveCompactionConfigResolverTest {

    private val userId = 1L
    private val presetId = 1L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val t0 = Instant.fromEpochMilliseconds(1_000L)

    private val preference = ConversationCompactionPreference(
        modelId = 1L,
        settingsId = 1L,
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L
    )

    /** The preset every case starts from: compaction enabled with no threshold override. */
    private val preset = ModelPresetEntity(
        id = presetId,
        name = "smart_model",
        displayName = "Smart model",
        description = "Preset bundling the first model with its chat settings",
        modelId = 1L,
        modelSettingsId = 1L,
        compactionEnabled = true,
        compactionThresholdTokens = null,
        createdAt = t0,
        updatedAt = t0
    )

    private val modelPresetDao = mockk<ModelPresetDao>()
    private val userPreferenceDao = mockk<UserPreferenceDao>()

    /** Builds the resolver under test against the mocked DAOs and the real JSON codec. */
    private fun resolver(): DefaultEffectiveCompactionConfigResolver = DefaultEffectiveCompactionConfigResolver(
        modelPresetDao = modelPresetDao,
        userPreferenceDao = userPreferenceDao,
        json = json
    )

    /** Stores the preset row the resolver reads; `null` stands for a preset the user does not own. */
    private fun stubPreset(row: ModelPresetEntity?) {
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)) } returns listOfNotNull(row)
    }

    /** Stores a raw preference value as the global row the resolver reads. */
    private fun stubGlobalPreference(row: UserPreferenceEntity?) {
        coEvery { userPreferenceDao.getGlobalPreference(any(), PreferenceKeys.CONVERSATION_COMPACTION) } returns row
    }

    /** Builds a global preference row holding the given preference's canonical JSON. */
    private fun preferenceRow(pref: ConversationCompactionPreference = preference): UserPreferenceEntity =
        UserPreferenceEntity(
            id = 1L,
            userId = userId,
            deviceId = null,
            scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = json.encodeToString(ConversationCompactionPreference.serializer(), pref),
            updatedAt = t0
        )

    /**
     * Builds the effective settings the resolver must derive from the given stored preference.
     *
     * @param pref Stored preference the settings are derived from.
     * @param thresholdTokens Effective threshold; defaults to the stored preference's own threshold.
     */
    private fun settingsOf(
        pref: ConversationCompactionPreference,
        thresholdTokens: Long = pref.thresholdTokens
    ): EffectiveCompactionSettings = EffectiveCompactionSettings(
        modelId = pref.modelId!!,
        settingsId = pref.settingsId!!,
        instruction = pref.instruction,
        systemMessage = pref.systemMessage,
        summaryLabel = pref.summaryLabel,
        thresholdTokens = thresholdTokens
    )

    /**
     * Resolves the turn for the given preset and stubbed global preference.
     *
     * @param presetRow Preset row the resolver reads; `null` when the user has no such preset.
     * @param prefRow Preference row the resolver reads; `null` when the user has none.
     * @return The resolver's result for that preset and preference.
     */
    private suspend fun resolveTurn(
        presetRow: ModelPresetEntity? = preset,
        prefRow: UserPreferenceEntity? = preferenceRow()
    ): Either<ConversationCompactionError.InvalidConfiguration, ResolvedCompactionConfig> {
        stubPreset(presetRow)
        stubGlobalPreference(prefRow)
        return resolver().resolve(userId = userId, presetId = presetId)
    }

    /**
     * Resolves the given stored preference and asserts the reported reason of the rejection.
     *
     * @param pref Stored preference expected to be rejected.
     * @param presetThresholdTokens Optional preset threshold override applied to the turn.
     * @param expectedReason Exact rejection reason expected.
     */
    private suspend fun assertRejected(
        pref: ConversationCompactionPreference,
        presetThresholdTokens: Long? = null,
        expectedReason: String
    ) {
        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(
            resolveTurn(
                presetRow = preset.copy(compactionThresholdTokens = presetThresholdTokens),
                prefRow = preferenceRow(pref)
            ).leftOrNull()
        )
        assertEquals(expectedReason, error.reason)
    }

    @Test
    fun `the preset is read exactly once per turn`() = runTest {
        resolveTurn()

        coVerify(exactly = 1) { modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)) }
    }

    @Test
    fun `a preset the user does not own is an invalid-configuration error`() = runTest {
        // The lookup is ownership-scoped, so a preset of another user is as unusable as a deleted one.
        val result = resolveTurn(presetRow = null)

        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(result.leftOrNull())
        assertTrue(error.reason.contains(presetId.toString()), error.reason)
        assertTrue(error.reason.contains(userId.toString()), error.reason)
        // No preset means nothing to derive a configuration from, so the preference is never consulted.
        coVerify(exactly = 0) { userPreferenceDao.getGlobalPreference(any(), any()) }
    }

    @Test
    fun `a non-positive preset threshold is an invalid-configuration error`() = runTest {
        // Both preset write paths reject such a value, so only a hand-edited row reaches this point; the
        // turn fails loudly instead of running with a meaningless threshold.
        val result = resolveTurn(presetRow = preset.copy(compactionThresholdTokens = 0L))

        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(result.leftOrNull())
        assertTrue(error.reason.contains("compaction threshold"), error.reason)
        assertTrue(error.reason.contains("0"), error.reason)
        coVerify(exactly = 0) { userPreferenceDao.getGlobalPreference(any(), any()) }
    }

    @Test
    fun `a preset-disabled turn is disabled before the preference is read`() = runTest {
        // The stored preference is deliberately malformed: a preset-disabled turn must not even read
        // it, so the malformation never surfaces as a configuration error.
        val malformedRow = UserPreferenceEntity(
            id = 1L, userId = userId, deviceId = null, scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = """{"thresholdTokens":1000}""", updatedAt = t0
        )

        val result = resolveTurn(presetRow = preset.copy(compactionEnabled = false), prefRow = malformedRow)

        assertEquals(ResolvedCompactionConfig.Disabled, result.getOrNull())
        coVerify(exactly = 0) { userPreferenceDao.getGlobalPreference(any(), any()) }
    }

    @Test
    fun `a preset-disabled turn stays disabled even with an enabled preference`() = runTest {
        val result = resolveTurn(presetRow = preset.copy(compactionEnabled = false))

        assertEquals(ResolvedCompactionConfig.Disabled, result.getOrNull())
    }

    @Test
    fun `an absent preference disables an enabled preset`() = runTest {
        val result = resolveTurn(presetRow = preset.copy(compactionThresholdTokens = 50L), prefRow = null)

        assertEquals(ResolvedCompactionConfig.Disabled, result.getOrNull())
    }

    @Test
    fun `a present but disabled preference disables an enabled preset`() = runTest {
        val result = resolveTurn(prefRow = preferenceRow(preference.copy(enabled = false)))

        assertEquals(ResolvedCompactionConfig.Disabled, result.getOrNull())
    }

    @Test
    fun `a disabled preference stays disabled even when its stored values are invalid`() = runTest {
        // The `enabled` kill switch is evaluated before the five value checks, so a row that disables
        // compaction while carrying invalid values (null ids, blank instruction, non-positive threshold)
        // still resolves to a disabled turn instead of rejecting every message the user sends.
        val invalidDisabled = preference.copy(
            enabled = false,
            modelId = null,
            settingsId = null,
            instruction = "   ",
            thresholdTokens = 0L
        )

        val result = resolveTurn(prefRow = preferenceRow(invalidDisabled))

        assertEquals(ResolvedCompactionConfig.Disabled, result.getOrNull())
    }

    @Test
    fun `a structurally invalid preference is an invalid-configuration error`() = runTest {
        // The row is malformed (required keys are missing), so the resolver reports the configuration
        // error as the Either left instead of a partially-usable value: the turn is rejected before it
        // starts, and no threshold hint is fabricated.
        val malformedRow = UserPreferenceEntity(
            id = 1L, userId = userId, deviceId = null, scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = """{"thresholdTokens":1000}""", updatedAt = t0
        )

        val result = resolveTurn(prefRow = malformedRow)

        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(result.leftOrNull())
        assertTrue(
            error.reason.startsWith("The conversation_compaction preference is structurally invalid:"),
            error.reason
        )
    }

    @Test
    fun `the effective threshold falls back to the preference when the preset has none`() = runTest {
        val resolved = assertIs<ResolvedCompactionConfig.Enabled>(resolveTurn().getOrNull())

        assertEquals(preference.thresholdTokens, resolved.settings.thresholdTokens)
        assertEquals(settingsOf(preference), resolved.settings)
    }

    @Test
    fun `a preset threshold overrides the preference threshold`() = runTest {
        val resolved = assertIs<ResolvedCompactionConfig.Enabled>(
            resolveTurn(presetRow = preset.copy(compactionThresholdTokens = 50_000L)).getOrNull()
        )

        assertEquals(50_000L, resolved.settings.thresholdTokens)
        // The effective settings are still preference-sourced: the auxiliary model/settings/instruction/
        // label stay as stored even when the preset overrides the threshold.
        assertEquals(settingsOf(preference, thresholdTokens = 50_000L), resolved.settings)
    }

    @Test
    fun `a preference omitting the threshold falls back to the documented default`() = runTest {
        // A row without `thresholdTokens` decodes with the 100_000 default; a preset without an override
        // therefore resolves that default as the effective threshold.
        val rowWithoutThreshold = UserPreferenceEntity(
            id = 1L, userId = userId, deviceId = null, scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = """{"modelId":1,"settingsId":1,"instruction":"Summarize faithfully"}""",
            updatedAt = t0
        )

        val resolved = assertIs<ResolvedCompactionConfig.Enabled>(resolveTurn(prefRow = rowWithoutThreshold).getOrNull())

        assertEquals(
            ConversationCompactionPreference.DEFAULT_COMPACTION_THRESHOLD_TOKENS,
            resolved.settings.thresholdTokens
        )
    }

    @Test
    fun `a preference without the enabled field still enables compaction`() = runTest {
        // Rows written before the enabled flag existed decode with the default `enabled = true`, so
        // nothing silently disables compaction for existing configurations.
        val rowWithoutEnabled = UserPreferenceEntity(
            id = 1L, userId = userId, deviceId = null, scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = """{"modelId":1,"settingsId":1,"instruction":"Summarize faithfully","thresholdTokens":1000}""",
            updatedAt = t0
        )

        val resolved = assertIs<ResolvedCompactionConfig.Enabled>(resolveTurn(prefRow = rowWithoutEnabled).getOrNull())

        assertEquals(settingsOf(preference.copy(enabled = true)), resolved.settings)
    }

    @Test
    fun `a stored preference without a model id is rejected even when the preset overrides the threshold`() =
        runTest {
            // A half-configured row cannot be compacted at all, so it fails the turn up front even
            // though the preset override would supply the threshold the turn would actually use.
            assertRejected(
                pref = preference.copy(modelId = null),
                presetThresholdTokens = 50_000L,
                expectedReason = "Compaction modelId is not set"
            )
        }

    @Test
    fun `a stored preference without a settings id is rejected even when the preset overrides the threshold`() =
        runTest {
            assertRejected(
                pref = preference.copy(settingsId = null),
                presetThresholdTokens = 50_000L,
                expectedReason = "Compaction settingsId is not set"
            )
        }

    @Test
    fun `a non-positive stored model id is rejected`() = runTest {
        assertRejected(
            pref = preference.copy(modelId = 0L),
            expectedReason = "Compaction modelId must be positive"
        )
    }

    @Test
    fun `a non-positive stored settings id is rejected`() = runTest {
        assertRejected(
            pref = preference.copy(settingsId = -3L),
            expectedReason = "Compaction settingsId must be positive"
        )
    }

    @Test
    fun `a blank stored instruction is rejected`() = runTest {
        assertRejected(
            pref = preference.copy(instruction = "   "),
            expectedReason = "Compaction instruction must not be blank"
        )
    }

    @Test
    fun `a non-positive stored threshold is rejected`() = runTest {
        assertRejected(
            pref = preference.copy(thresholdTokens = 0L),
            expectedReason = "Compaction thresholdTokens must be positive"
        )
    }

    @Test
    fun `a non-positive stored threshold is rejected even when the preset overrides the threshold`() = runTest {
        // The override decides the threshold the turn would USE; it must never decide whether the
        // stored configuration is ACCEPTED.
        assertRejected(
            pref = preference.copy(thresholdTokens = -1L),
            presetThresholdTokens = 50_000L,
            expectedReason = "Compaction thresholdTokens must be positive"
        )
    }
}
