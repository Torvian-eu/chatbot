package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Maximum number of characters allowed in a model-preset name.
 *
 * This mirrors the `name` varchar(255) column of the server's `model_presets` table (see
 * `ModelPresetTable`). It is shared by UI text-field limits and server validation so both layers
 * stay consistent with the database schema, mirroring
 * [eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH].
 */
const val MAX_MODEL_PRESET_NAME_LENGTH = 255

/**
 * Shared, serializable representation of a user-owned model preset.
 *
 * A model preset is a named bundle of "one [LLMModel] + one [ModelSettings] profile" that is the
 * **sole source of truth** for an agent role's LLM configuration. Re-pointing a preset (e.g. to a
 * newly released model, or to a variant settings profile that differs only in
 * `customParams = {"provider":{"only":["<provider name>"]}}`) switches every role bound to that
 * preset at once — that reuse is the whole reason the bundle is a named entity rather than two
 * columns per role.
 *
 * Invariants and rules:
 * - A preset **references** an existing settings profile; it never carries a settings payload and
 *   never overrides `customParams`. Switching OpenRouter provider routing therefore means wrapping a
 *   variant settings profile in a second preset, not editing the preset.
 * - When both [modelId] and [modelSettingsId] are non-null, [modelId] must equal the referenced
 *   settings' own model id. This is validated on preset write, re-checked when a preset is attached
 *   to a role, and checked defensively at turn time (a settings profile can be re-pointed to another
 *   model after the preset was written).
 * - Preset references must exist **and** be `READ`-accessible to the requesting user.
 * - The preset layer deliberately does **not** restrict [LLMModelType]: presets are reusable for
 *   non-chat purposes (e.g. embeddings). Chat capability is enforced only where a preset drives an
 *   agent-role turn.
 * - Both references are nulled (`ON DELETE SET NULL`) when the referenced model/settings row is
 *   deleted. Such a preset stays valid and attachable to a role, but the role becomes non-sendable
 *   until the preset's reference is re-pointed.
 *
 * @property id Immutable, database-generated identifier.
 * @property name The preset name. Unique per owner user, trimmed, non-blank and at most
 *            [MAX_MODEL_PRESET_NAME_LENGTH] characters; enforced by the server (the column itself is
 *            not unique).
 * @property displayName Optional human-friendly display name; clients fall back to [name].
 * @property description Free-form description of the preset's purpose.
 * @property modelId Identifier of the referenced [LLMModel], or `null` when the preset has no model or
 *            the model was deleted (`ON DELETE SET NULL`).
 * @property modelSettingsId Identifier of the referenced [ModelSettings] profile, or `null` when the
 *            preset has no settings profile or the profile was deleted (`ON DELETE SET NULL`).
 * @property createdAt Timestamp when the preset was created. Server-managed, read-only output: the
 *            write requests never carry it, so a client cannot forge or clear it.
 * @property updatedAt Timestamp when the preset was last updated. Server-managed and read-only like
 *            [createdAt].
 */
@Serializable
data class ModelPresetDto(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long?,
    val modelSettingsId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)
