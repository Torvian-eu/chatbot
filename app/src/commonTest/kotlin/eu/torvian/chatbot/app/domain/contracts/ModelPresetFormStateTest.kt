package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for the model-preset form draft: validation (name and the optional compaction threshold —
 * both references may be null), request mapping (including the blank threshold's "use the user
 * preference" meaning), the edit-draft round trip and error propagation.
 */
class ModelPresetFormStateTest {

    private fun preset(
        id: Long = 7L,
        name: String = "gpt-4o default",
        displayName: String? = null,
        description: String = "",
        modelId: Long? = 10L,
        modelSettingsId: Long? = 20L,
        automaticCompactionEnabled: Boolean = true,
        compactionThresholdTokens: Long? = null
    ): ModelPresetDto = ModelPresetDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        automaticCompactionEnabled = automaticCompactionEnabled,
        compactionThresholdTokens = compactionThresholdTokens,
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id)
    )

    @Test
    fun `empty form is a new draft without references`() {
        val form = createEmptyModelPresetForm()

        assertEquals(FormMode.NEW, form.mode)
        assertNull(form.presetId)
        assertEquals("", form.name)
        assertNull(form.modelId)
        assertNull(form.modelSettingsId)
    }

    @Test
    fun `validate rejects a blank name`() {
        assertEquals("Preset name cannot be empty.", createEmptyModelPresetForm().copy(name = "   ").validate())
    }

    @Test
    fun `validate rejects a name longer than the shared maximum`() {
        val tooLong = "a".repeat(MAX_MODEL_PRESET_NAME_LENGTH + 1)

        assertEquals(
            "Preset name cannot exceed $MAX_MODEL_PRESET_NAME_LENGTH characters.",
            createEmptyModelPresetForm().copy(name = tooLong).validate()
        )
    }

    @Test
    fun `validate accepts a name-only draft`() {
        // The server accepts a preset without a model and/or without a settings profile, and the
        // model-gated settings picker is a UI affordance rather than a draft rule.
        assertNull(createEmptyModelPresetForm().copy(name = "name only").validate())
        assertNull(
            createEmptyModelPresetForm()
                .copy(name = "with model", modelId = 10L, modelSettingsId = 20L)
                .validate()
        )
    }

    @Test
    fun `validate accepts the settings-only state so an unrelated edit round-trips it`() {
        // Reachable through REST/preset tools, not through this form: the draft must stay valid and
        // keep its settings reference instead of being "repaired" into a clear.
        val form = createEmptyModelPresetForm()
            .copy(name = "degenerate", modelId = null, modelSettingsId = 20L)

        assertNull(form.validate())
        assertEquals(20L, form.toUpdateRequest().modelSettingsId)
        assertNull(form.toUpdateRequest().modelId)
    }

    @Test
    fun `toCreateRequest trims the name and blanks the display name`() {
        val form = createEmptyModelPresetForm().copy(
            name = "  Preset  ",
            displayName = "   ",
            description = "  A description  ",
            modelId = 10L,
            modelSettingsId = 20L
        )

        val request = form.toCreateRequest()

        assertEquals("Preset", request.name)
        assertNull(request.displayName)
        assertEquals("A description", request.description)
        assertEquals(10L, request.modelId)
        assertEquals(20L, request.modelSettingsId)
    }

    @Test
    fun `toCreateRequest maps null references through unchanged`() {
        val request = createEmptyModelPresetForm().copy(name = "Name only").toCreateRequest()

        assertNull(request.modelId)
        assertNull(request.modelSettingsId)
    }

    @Test
    fun `toUpdateRequest is a full replacement carrying both references`() {
        val form = ModelPresetFormState(
            mode = FormMode.EDIT,
            presetId = 7L,
            name = "Renamed",
            displayName = "Display",
            description = "Desc",
            modelId = 11L,
            modelSettingsId = 21L
        )

        val request = form.toUpdateRequest()

        assertEquals("Renamed", request.name)
        assertEquals("Display", request.displayName)
        assertEquals("Desc", request.description)
        assertEquals(11L, request.modelId)
        assertEquals(21L, request.modelSettingsId)
    }

    @Test
    fun `empty form defaults to compaction enabled with the user preference threshold`() {
        val form = createEmptyModelPresetForm()

        assertEquals(true, form.automaticCompactionEnabled)
        assertEquals("", form.compactionThresholdTokensText)
        assertEquals(true, form.toCreateRequest().automaticCompactionEnabled)
        assertNull(form.toCreateRequest().compactionThresholdTokens)
    }

    @Test
    fun `validate rejects a non-numeric or non-positive threshold text`() {
        val nonNumeric = createEmptyModelPresetForm().copy(name = "Name", compactionThresholdTokensText = "abc")
        assertEquals(
            "Compaction threshold must be a whole number, or left empty to use the user preference threshold.",
            nonNumeric.validate()
        )

        val zero = createEmptyModelPresetForm().copy(name = "Name", compactionThresholdTokensText = "0")
        assertEquals(
            "Compaction threshold must be at least 1, or left empty to use the user preference threshold.",
            zero.validate()
        )

        val negative = createEmptyModelPresetForm().copy(name = "Name", compactionThresholdTokensText = "-500")
        assertEquals(
            "Compaction threshold must be at least 1, or left empty to use the user preference threshold.",
            negative.validate()
        )
    }

    @Test
    fun `validate accepts a blank and a positive threshold text`() {
        assertNull(
            createEmptyModelPresetForm().copy(name = "Name", compactionThresholdTokensText = "   ").validate()
        )
        assertNull(createEmptyModelPresetForm().copy(name = "Name", compactionThresholdTokensText = "1").validate())
    }

    @Test
    fun `requests carry the compaction fields with a blank threshold meaning the fallback`() {
        val form = createEmptyModelPresetForm().copy(
            name = "Name",
            automaticCompactionEnabled = false,
            compactionThresholdTokensText = "  50000  "
        )

        assertEquals(false, form.toCreateRequest().automaticCompactionEnabled)
        assertEquals(50_000L, form.toCreateRequest().compactionThresholdTokens)
        assertEquals(false, form.toUpdateRequest().automaticCompactionEnabled)
        assertEquals(50_000L, form.toUpdateRequest().compactionThresholdTokens)

        val blank = form.copy(compactionThresholdTokensText = "")
        assertNull(blank.toCreateRequest().compactionThresholdTokens)
        assertNull(blank.toUpdateRequest().compactionThresholdTokens)
    }

    @Test
    fun `toEditFormState renders an unset threshold as a blank field`() {
        val withOverride = preset(automaticCompactionEnabled = false, compactionThresholdTokens = 50_000L).toEditFormState()
        assertEquals(false, withOverride.automaticCompactionEnabled)
        assertEquals("50000", withOverride.compactionThresholdTokensText)

        val fallback = preset(automaticCompactionEnabled = true, compactionThresholdTokens = null).toEditFormState()
        assertEquals(true, fallback.automaticCompactionEnabled)
        assertEquals("", fallback.compactionThresholdTokensText)
    }

    @Test
    fun `toEditFormState round-trips the compaction configuration`() {
        val original = preset(automaticCompactionEnabled = false, compactionThresholdTokens = 12_345L)

        val request = original.toEditFormState().toUpdateRequest()

        assertEquals(original.automaticCompactionEnabled, request.automaticCompactionEnabled)
        assertEquals(original.compactionThresholdTokens, request.compactionThresholdTokens)
    }

    @Test
    fun `toEditFormState copies both references verbatim`() {
        val form = preset(
            id = 7L,
            name = "gpt-4o default",
            displayName = "Default",
            description = "Primary",
            modelId = 10L,
            modelSettingsId = 20L
        ).toEditFormState()

        assertEquals(FormMode.EDIT, form.mode)
        assertEquals(7L, form.presetId)
        assertEquals("gpt-4o default", form.name)
        assertEquals("Default", form.displayName)
        assertEquals("Primary", form.description)
        assertEquals(10L, form.modelId)
        assertEquals(20L, form.modelSettingsId)
    }

    @Test
    fun `toEditFormState keeps a null display name empty and null references null`() {
        val form = preset(displayName = null, modelId = null, modelSettingsId = null).toEditFormState()

        assertEquals("", form.displayName)
        assertNull(form.modelId)
        assertNull(form.modelSettingsId)
    }

    @Test
    fun `withError sets the error message without touching other fields`() {
        val form = createEmptyModelPresetForm().copy(name = "Name", modelId = 10L, modelSettingsId = 20L)

        val withError = form.withError("Something went wrong")

        assertEquals("Something went wrong", withError.errorMessage)
        assertEquals("Name", withError.name)
        assertEquals(10L, withError.modelId)
        assertEquals(20L, withError.modelSettingsId)
        assertNull(form.errorMessage)
    }
}
