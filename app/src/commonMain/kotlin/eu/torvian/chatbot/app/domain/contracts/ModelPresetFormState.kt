package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH
import eu.torvian.chatbot.common.models.llm.ModelPresetDto

/**
 * Mutable draft of a model preset being created or edited in the management form.
 *
 * All fields are held as plain values so the form can build a [CreateModelPresetRequest] or
 * [UpdateModelPresetRequest] on save.
 *
 * Both references are optional: the server accepts a preset without a model and/or without a settings
 * profile, and such a preset stays valid and attachable (it merely cannot drive a turn). The settings
 * *picker* is gated on a selected model (a settings profile belongs to exactly one model), but that
 * gate is a UI affordance only: it is deliberately **not** expressed in [validate], so a persisted
 * reference that the picker cannot offer — the reachable-by-REST "settings profile without a model"
 * state — survives an unrelated edit instead of being silently cleared.
 *
 * @property mode Whether this draft creates a new preset or edits an existing one.
 * @property presetId Existing preset id while editing.
 * @property name Unique (per owner) preset name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the preset's purpose.
 * @property modelId Selected model reference, or null for "No model".
 * @property modelSettingsId Selected settings-profile reference, or null for "No settings profile".
 * @property errorMessage Optional validation error surfaced to the form.
 */
data class ModelPresetFormState(
    val mode: FormMode = FormMode.NEW,
    val presetId: Long? = null,
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val modelId: Long? = null,
    val modelSettingsId: Long? = null,
    val errorMessage: String? = null
) {

    /**
     * Copies this draft with an updated error message, preserving all other fields.
     */
    fun withError(errorMessage: String?): ModelPresetFormState = copy(errorMessage = errorMessage)

    /**
     * Validates the required fields. Only the name is mandatory; both references may be null because
     * the server accepts a preset without a model, without a settings profile, or without either.
     *
     * Per-owner name uniqueness and reference accessibility/model-agreement are **not** pre-checked
     * here: the server owns those rules and reports them as typed 4xx errors that the dialog shows.
     *
     * @return A human-readable validation message, or null when the draft is valid.
     */
    fun validate(): String? {
        if (name.isBlank()) return "Preset name cannot be empty."
        if (name.length > MAX_MODEL_PRESET_NAME_LENGTH) {
            return "Preset name cannot exceed $MAX_MODEL_PRESET_NAME_LENGTH characters."
        }
        return null
    }

    /**
     * Builds a [CreateModelPresetRequest] from this draft. Only valid when [mode] is NEW and
     * [validate] returns null.
     *
     * A null reference is sent as-is (it means "no model"/"no settings profile"); the server treats
     * both as valid.
     */
    fun toCreateRequest(): CreateModelPresetRequest = CreateModelPresetRequest(
        name = name.trim(),
        displayName = displayName.trim().takeIf { it.isNotBlank() },
        description = description.trim(),
        modelId = modelId,
        modelSettingsId = modelSettingsId
    )

    /**
     * Builds an [UpdateModelPresetRequest] from this draft. Only valid when [mode] is EDIT and
     * [validate] returns null.
     *
     * The update is a full replacement, so the draft's references are sent verbatim: an untouched
     * persisted reference (including a settings profile on a model-less preset) round-trips unchanged.
     */
    fun toUpdateRequest(): UpdateModelPresetRequest = UpdateModelPresetRequest(
        name = name.trim(),
        displayName = displayName.trim().takeIf { it.isNotBlank() },
        description = description.trim(),
        modelId = modelId,
        modelSettingsId = modelSettingsId
    )
}

/**
 * Creates an empty draft for a new preset: no name, no references.
 *
 * @return A new [ModelPresetFormState] in NEW mode.
 */
fun createEmptyModelPresetForm(): ModelPresetFormState = ModelPresetFormState(
    mode = FormMode.NEW
)

/**
 * Creates an edit draft from an existing preset, preserving both references exactly as persisted.
 *
 * Copying the references verbatim is what makes the degenerate model-less-with-settings state safe:
 * the form displays it read-only and saves it back untouched unless the user deliberately changes it.
 *
 * @receiver The preset to edit.
 * @return A [ModelPresetFormState] in EDIT mode pre-filled from the preset.
 */
fun ModelPresetDto.toEditFormState(): ModelPresetFormState = ModelPresetFormState(
    mode = FormMode.EDIT,
    presetId = id,
    name = name,
    displayName = displayName ?: "",
    description = description,
    modelId = modelId,
    modelSettingsId = modelSettingsId
)

/**
 * Consolidated dialog state for the Model Presets management tab.
 */
sealed class ModelPresetDialogState {
    /** No dialog is currently visible. */
    object None : ModelPresetDialogState()

    /** Add-preset form dialog. */
    data class AddPreset(
        val formState: ModelPresetFormState
    ) : ModelPresetDialogState()

    /** Edit-preset form dialog. */
    data class EditPreset(
        val preset: ModelPresetDto,
        val formState: ModelPresetFormState
    ) : ModelPresetDialogState()

    /** Delete-preset confirmation dialog. */
    data class DeletePreset(
        val preset: ModelPresetDto
    ) : ModelPresetDialogState()
}
