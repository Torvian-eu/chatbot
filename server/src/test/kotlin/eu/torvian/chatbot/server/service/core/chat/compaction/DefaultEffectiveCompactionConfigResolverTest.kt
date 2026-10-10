package eu.torvian.chatbot.server.service.core.chat.compaction

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the resolution matrix of the effective per-turn compaction configuration: the preset read and
 * its validation, the preference read and decode, the enabling rule (both scopes ANDed, an absent row
 * counted as disabled, an undecodable row keeping the enabled default), the usage checks of the stored
 * value, the threshold precedence, and the rule that an unusable configuration rejects the turn only
 * while automatic compaction is enabled (otherwise the turn proceeds with the raw thread).
 *
 * The JSON codec is real (only the DAOs are mocked), so the decode rules that decide between an unusable
 * and a usable configuration are exercised as they run in production.
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

    /** The preset every case starts from: automatic compaction enabled with no threshold override. */
    private val preset = ModelPresetEntity(
        id = presetId,
        name = "smart_model",
        displayName = "Smart model",
        description = "Preset bundling the first model with its chat settings",
        modelId = 1L,
        modelSettingsId = 1L,
        automaticCompactionEnabled = true,
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

    /** Builds a global preference row holding a raw, deliberately malformed JSON value. */
    private fun rawPreferenceRow(rawValue: String): UserPreferenceEntity =
        UserPreferenceEntity(
            id = 1L,
            userId = userId,
            deviceId = null,
            scopeId = "GLOBAL",
            prefKey = PreferenceKeys.CONVERSATION_COMPACTION,
            prefValue = rawValue,
            updatedAt = t0
        )

    /**
     * Builds the effective settings the resolver must derive from the given stored preference.
     *
     * Whether automatic compaction is enabled for the resolved turn is asserted separately, because that
     * flag lives on the resolution rather than on the settings.
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
    ): ResolvedCompactionConfig {
        stubPreset(presetRow)
        stubGlobalPreference(prefRow)
        return resolver().resolve(userId = userId, presetId = presetId)
    }

    /** Reads the usable resolution, failing the test when no usable configuration was resolved. */
    private fun usable(resolved: ResolvedCompactionConfig): ResolvedCompactionConfig.Usable =
        assertIs<ResolvedCompactionConfig.Usable>(resolved)

    /** Reads the unusable resolution, failing the test when a usable configuration was resolved. */
    private fun unusable(resolved: ResolvedCompactionConfig): ResolvedCompactionConfig.Unusable =
        assertIs<ResolvedCompactionConfig.Unusable>(resolved)

    /**
     * Resolves the given stored preference and asserts that the resolution is unusable with the expected
     * reason while automatic compaction stays enabled.
     *
     * @param pref Stored preference expected to be unusable.
     * @param presetThresholdTokens Optional preset threshold override applied to the turn.
     * @param expectedReason Exact reason expected.
     */
    private suspend fun assertUnusable(
        pref: ConversationCompactionPreference,
        presetThresholdTokens: Long? = null,
        expectedReason: String
    ) {
        val negative = unusable(
            resolveTurn(
                presetRow = preset.copy(compactionThresholdTokens = presetThresholdTokens),
                prefRow = preferenceRow(pref)
            )
        )
        assertEquals(expectedReason, negative.reason)
        assertTrue(negative.automaticCompactionEnabled)
    }

    @Test
    fun `a preset with automatic compaction disabled and an invalid stored value is unusable`() =
        runTest {
            // Enabling is preset AND preference, so a preset with the flag off turns the very same invalid
            // stored value into a turn that proceeds with the raw thread.
            val resolved = resolveTurn(
                presetRow = preset.copy(automaticCompactionEnabled = false),
                prefRow = preferenceRow(preference.copy(modelId = null))
            )

            val negative = unusable(resolved)
            assertEquals("Compaction modelId is not set", negative.reason)
            assertFalse(negative.automaticCompactionEnabled)
        }

    @Test
    fun `the preset is read exactly once per turn`() = runTest {
        resolveTurn()

        coVerify(exactly = 1) { modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)) }
    }

    @Test
    fun `a preset the user does not own is unusable, disables automatic compaction and never reads the preference`() =
        runTest {
            // The lookup is ownership-scoped, so a preset of another user is as unusable as a deleted one.
            // Its flag is unknown, so the turn can never be rejected for compaction reasons.
            val resolved = resolveTurn(presetRow = null)

            val negative = unusable(resolved)
            assertTrue(negative.reason.contains(presetId.toString()), negative.reason)
            assertTrue(negative.reason.contains(userId.toString()), negative.reason)
            assertFalse(negative.automaticCompactionEnabled)
            coVerify(exactly = 0) { userPreferenceDao.getGlobalPreference(any(), any()) }
        }

    @Test
    fun `a non-positive preset threshold reports an unusable turn with automatic compaction enabled`() =
        runTest {
            // Both preset write paths reject such a value, so only a hand-edited row reaches this point; an
            // enabled turn must fail loudly instead of running with a meaningless threshold.
            val resolved = resolveTurn(presetRow = preset.copy(compactionThresholdTokens = 0L))

            val negative = unusable(resolved)
            assertTrue(negative.automaticCompactionEnabled)
            assertTrue(negative.reason.contains("compaction threshold"), negative.reason)
            assertTrue(negative.reason.contains("0"), negative.reason)
        }

    @Test
    fun `a non-positive preset threshold reports automatic compaction as disabled when the preset disables it`() = runTest {
        val resolved = resolveTurn(
            presetRow = preset.copy(automaticCompactionEnabled = false, compactionThresholdTokens = 0L)
        )

        assertFalse(unusable(resolved).automaticCompactionEnabled)
    }

    @Test
    fun `enabling automatic compaction requires both scopes`() = runTest {
        assertTrue(usable(resolveTurn()).automaticCompactionEnabled)
        assertFalse(
            usable(resolveTurn(presetRow = preset.copy(automaticCompactionEnabled = false)))
                .automaticCompactionEnabled
        )
        assertFalse(
            usable(resolveTurn(prefRow = preferenceRow(preference.copy(automaticCompactionEnabled = false))))
                .automaticCompactionEnabled
        )
        // An absent row cannot enable anything: its flag is unknown, so the configuration is simply
        // absent.
        assertFalse(unusable(resolveTurn(prefRow = null)).automaticCompactionEnabled)
    }

    @Test
    fun `an absent preference row is unusable with automatic compaction disabled`() = runTest {
        val resolved = resolveTurn(prefRow = null)

        val negative = unusable(resolved)
        assertTrue(negative.reason.contains("No conversation_compaction preference is stored"))
        assertFalse(negative.automaticCompactionEnabled)
    }

    @Test
    fun `a disabled preference keeps the stored configuration usable`() = runTest {
        // Disabling must not make the configuration unusable: a manual request still needs it.
        val resolved = resolveTurn(prefRow = preferenceRow(preference.copy(automaticCompactionEnabled = false)))
        val configuration = usable(resolved)

        assertEquals(settingsOf(preference), configuration.settings)
        assertFalse(configuration.automaticCompactionEnabled)
    }

    @Test
    fun `a disabled preference with invalid stored values is unusable`() =
        runTest {
            // The value checks do not short-circuit on the flag: the unusable configuration is reported so a
            // manual request explains itself, while the turn with automatic compaction disabled proceeds.
            val invalidDisabled = preference.copy(
                automaticCompactionEnabled = false,
                modelId = null,
                settingsId = null,
                instruction = "   ",
                thresholdTokens = 0L
            )

            val negative = unusable(resolveTurn(prefRow = preferenceRow(invalidDisabled)))
            assertEquals("Compaction modelId is not set", negative.reason)
            assertFalse(negative.automaticCompactionEnabled)
        }

    @Test
    fun `a structurally invalid preference reports the decode message with automatic compaction enabled`() = runTest {
        // Required keys are missing, so nothing can be compacted; the decode message is reported and the
        // the enabled flag tells preparation to reject the turn before it starts.
        val resolved = resolveTurn(prefRow = rawPreferenceRow("""{"thresholdTokens":1000}"""))

        val negative = unusable(resolved)
        assertTrue(
            negative.reason.startsWith("The conversation_compaction preference is structurally invalid:"),
            negative.reason
        )
        assertTrue(negative.automaticCompactionEnabled)
    }

    @Test
    fun `an undecodable preference row reports automatic compaction as enabled when the preset does not disable it`() =
        runTest {
            // The field's default describes a stored row that omits it, so an unreadable row keeps the
            // enabled default, exactly as before the split.
            val resolved = resolveTurn(prefRow = rawPreferenceRow("""{"thresholdTokens":1000}"""))

            assertTrue(unusable(resolved).automaticCompactionEnabled)
        }

    @Test
    fun `the effective threshold falls back to the preference when the preset has none`() = runTest {
        val resolved = resolveTurn()

        assertEquals(settingsOf(preference), usable(resolved).settings)
    }

    @Test
    fun `a preset threshold overrides the preference threshold`() = runTest {
        val resolved = resolveTurn(presetRow = preset.copy(compactionThresholdTokens = 50_000L))

        // The effective settings are still preference-sourced: the auxiliary model/settings/instruction/
        // label stay as stored even when the preset overrides the threshold.
        assertEquals(settingsOf(preference, thresholdTokens = 50_000L), usable(resolved).settings)
    }

    @Test
    fun `a preference omitting the threshold falls back to the documented default`() = runTest {
        // A row without `thresholdTokens` decodes with the 100_000 default; a preset without an override
        // therefore resolves that default as the effective threshold.
        val resolved = resolveTurn(
            prefRow = rawPreferenceRow("""{"modelId":1,"settingsId":1,"instruction":"Summarize faithfully"}""")
        )

        assertEquals(
            ConversationCompactionPreference.DEFAULT_COMPACTION_THRESHOLD_TOKENS,
            usable(resolved).settings.thresholdTokens
        )
    }

    @Test
    fun `a preference without the automatic flag stays enabled`() = runTest {
        // Rows written before the flag existed decode with the default `true`, so nothing silently
        // disables automatic compaction for existing configurations.
        val resolved = resolveTurn(
            prefRow = rawPreferenceRow(
                """{"modelId":1,"settingsId":1,"instruction":"Summarize faithfully","thresholdTokens":1000}"""
            )
        )

        assertEquals(settingsOf(preference), usable(resolved).settings)
        assertTrue(usable(resolved).automaticCompactionEnabled)
    }

    @Test
    fun `a legacy enabled key is ignored and the row stays enabled`() = runTest {
        // The renamed field takes its enabled default and the unknown key is ignored, so a development
        // instance that had disabled compaction silently re-enables until the row is re-saved.
        val resolved = resolveTurn(
            prefRow = rawPreferenceRow(
                """{"modelId":1,"settingsId":1,"instruction":"Summarize faithfully","thresholdTokens":1000,"enabled":false}"""
            )
        )

        assertTrue(usable(resolved).automaticCompactionEnabled)
    }

    @Test
    fun `a stored preference without a model id is unusable even when the preset overrides the threshold`() =
        runTest {
            // A half-configured row cannot be compacted at all, so it is reported up front even though
            // the preset override would supply the threshold the turn would actually use.
            assertUnusable(
                pref = preference.copy(modelId = null),
                presetThresholdTokens = 50_000L,
                expectedReason = "Compaction modelId is not set"
            )
        }

    @Test
    fun `a stored preference without a settings id is unusable even when the preset overrides the threshold`() =
        runTest {
            assertUnusable(
                pref = preference.copy(settingsId = null),
                presetThresholdTokens = 50_000L,
                expectedReason = "Compaction settingsId is not set"
            )
        }

    @Test
    fun `a non-positive stored model id is unusable`() = runTest {
        assertUnusable(
            pref = preference.copy(modelId = 0L),
            expectedReason = "Compaction modelId must be positive"
        )
    }

    @Test
    fun `a non-positive stored settings id is unusable`() = runTest {
        assertUnusable(
            pref = preference.copy(settingsId = -3L),
            expectedReason = "Compaction settingsId must be positive"
        )
    }

    @Test
    fun `a blank stored instruction is unusable`() = runTest {
        assertUnusable(
            pref = preference.copy(instruction = "   "),
            expectedReason = "Compaction instruction must not be blank"
        )
    }

    @Test
    fun `a non-positive stored threshold is unusable`() = runTest {
        assertUnusable(
            pref = preference.copy(thresholdTokens = 0L),
            expectedReason = "Compaction thresholdTokens must be positive"
        )
    }

    @Test
    fun `a non-positive stored threshold is unusable even when the preset overrides the threshold`() = runTest {
        // The override decides the threshold the turn would USE; it must never decide whether the stored
        // configuration is usable.
        assertUnusable(
            pref = preference.copy(thresholdTokens = -1L),
            presetThresholdTokens = 50_000L,
            expectedReason = "Compaction thresholdTokens must be positive"
        )
    }
}
