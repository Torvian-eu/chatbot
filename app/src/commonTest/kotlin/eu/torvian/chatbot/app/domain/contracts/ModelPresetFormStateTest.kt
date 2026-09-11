package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for the model-preset form draft: validation (name only — both references may be null),
 * request mapping, the edit-draft round trip and error propagation.
 */
class ModelPresetFormStateTest {

    private fun preset(
        id: Long = 7L,
        name: String = "gpt-4o default",
        displayName: String? = null,
        description: String = "",
        modelId: Long? = 10L,
        modelSettingsId: Long? = 20L
    ): ModelPresetDto = ModelPresetDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
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
