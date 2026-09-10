package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.llm.ModelPresetDto

/**
 * Formats the concise, non-JSON operation summaries returned by the mutating model-preset tools.
 *
 * `update_model_preset` and `delete_model_preset` deliberately do **not** return the full
 * [ModelPresetDto] JSON: they return a one-line plain-text description of the operation they just
 * completed (mirroring the project and agent-role mutating tools) so the LLM context stays lean.
 * The caller needs nothing but the preset id afterwards, which the summaries carry.
 *
 * `create_model_preset` is the exception among the mutating tools — like `create_project` it
 * returns the created preset's full JSON (see [CreateModelPresetTool]), because the caller must
 * learn the server-generated id and timestamps to attach the preset in the same turn. The read-side
 * tools (`list_model_presets`, `read_model_preset`) return full JSON as well.
 */

/**
 * Formats the summary for a completed `update_model_preset` operation.
 *
 * @param preset The preset state after the update (as returned by the model-preset service).
 * @return Plain text like `Updated model preset 'smart_model' (id: 3).` (never JSON).
 */
internal fun formatUpdatedModelPreset(preset: ModelPresetDto): String =
    "Updated model preset '${preset.name}' (id: ${preset.id})."

/**
 * Formats the summary for a completed `delete_model_preset` operation.
 *
 * Deleting a preset does not delete the agent roles bound to it (they merely lose the reference and
 * become non-sendable), so the summary reports the deletion only; computing an affected-role count
 * would require a role dependency for no requirement.
 *
 * @param presetId The id of the deleted preset (the delete service returns no payload, so the
 *            message carries the id the caller supplied).
 * @return Plain text like `Deleted model preset (id: 3).` (never JSON).
 */
internal fun formatDeletedModelPreset(presetId: Long): String =
    "Deleted model preset (id: $presetId)."
