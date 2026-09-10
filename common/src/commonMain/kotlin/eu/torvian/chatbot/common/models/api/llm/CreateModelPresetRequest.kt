package eu.torvian.chatbot.common.models.api.llm

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new user-owned model preset.
 *
 * The requesting user becomes the preset's sole owner; the name must be unique among the user's
 * existing presets (enforced by the server). Both references are optional: a preset may be created
 * without a model and/or without a settings profile (e.g. to reserve a name first), though such a
 * preset cannot drive an agent-role turn until its references are set through
 * [UpdateModelPresetRequest]. When both references are provided they must agree: the settings profile
 * must belong to [modelId], and both must be `READ`-accessible to the requesting user.
 *
 * @property name The preset name, non-blank and at most
 *            [eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH] characters. The
 *            server trims it and rejects a blank/too-long value.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the preset's purpose.
 * @property modelId Optional identifier of the LLM model the preset bundles. The model must be
 *            accessible to the requesting user; a missing or inaccessible id is rejected by the
 *            server.
 * @property modelSettingsId Optional identifier of the settings profile the preset bundles. The
 *            profile must be accessible to the requesting user and, when [modelId] is also present,
 *            must belong to that model. The preset layer does not restrict the settings' model type.
 */
@Serializable
data class CreateModelPresetRequest(
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long? = null,
    val modelSettingsId: Long? = null
)
