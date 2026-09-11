package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Client-side verdict on whether an agent role can drive a conversation turn.
 *
 * This is a **hint only**: it mirrors the server's turn-preparation checks so the settings UI can
 * explain *why* a role will fail, but the server stays authoritative. A role reported as
 * [Sendable] may still be rejected at turn time (e.g. because of a change made by another client),
 * and a role reported as [NotSendable] is deliberately still savable — only sending fails, with the
 * server's model-configuration error.
 */
sealed interface AgentRoleSendability {

    /**
     * The role has a preset whose model and settings profile are present, chat-capable and in
     * agreement, so a turn can be prepared for it.
     */
    data object Sendable : AgentRoleSendability

    /**
     * The role cannot drive a turn.
     *
     * @property reason Short, user-facing explanation shown next to the flag (mirrors the wording of
     *            the server's model-configuration error closely enough for the user to recognise the
     *            failure).
     */
    data class NotSendable(val reason: String) : AgentRoleSendability
}

/**
 * Resolves the [AgentRoleSendability] of [role] from its preset and settings-profile references.
 *
 * The rules, in evaluation order (each yields a distinct reason):
 * 1. no `modelPresetId` — the role is preset-less;
 * 2. no effective model — the attached preset's model reference is unset or its model was deleted;
 * 3. no effective settings reference — the preset's settings reference is unset or the profile was
 *    deleted;
 * 4. the effective settings profile could not be resolved on the client (deleted, or not accessible
 *    to this user);
 * 5. the settings profile is not a chat-like profile (`CHAT`/`RESPONSES`);
 * 6. the settings profile belongs to a different model than the preset's.
 *
 * The effective model/settings come from the role's server-resolved values first
 * ([AgentRoleDto.modelId]/[AgentRoleDto.modelSettingsId]); [preset] is the fallback for a role that
 * has no server-resolved values yet — i.e. an unsaved form draft, which only carries the preset
 * reference. Preferring the role's own values keeps a stale preset cache from overriding fresh role
 * data, while the preset fallback lets the form flag its pending selection.
 *
 * @param role The role (or a draft-shaped stand-in for it) to evaluate.
 * @param preset The preset referenced by `role.modelPresetId`, resolved through the preset stream, or
 *            null when the role has no preset or the stream has not resolved it yet.
 * @param settings The settings profile referenced by the role/preset, resolved through the settings
 *            stream, or null when it is unknown to this client.
 * @return [AgentRoleSendability.Sendable] when the role can drive a turn, otherwise
 *         [AgentRoleSendability.NotSendable] with the user-facing reason.
 */
fun resolveAgentRoleSendability(
    role: AgentRoleDto,
    preset: ModelPresetDto?,
    settings: ModelSettings?
): AgentRoleSendability {
    if (role.modelPresetId == null) {
        return AgentRoleSendability.NotSendable(
            "No model preset is attached; the role has no model or settings profile to send with."
        )
    }

    // The role's own values are server-resolved and therefore win; the preset covers a draft, whose
    // derived ids are not known until it is saved and echoed back.
    val effectiveModelId = role.modelId ?: preset?.modelId
    if (effectiveModelId == null) {
        return AgentRoleSendability.NotSendable(
            "The attached preset has no usable model (its model reference is unset, or the model was deleted)."
        )
    }

    val effectiveSettingsId = role.modelSettingsId ?: preset?.modelSettingsId
    if (effectiveSettingsId == null) {
        return AgentRoleSendability.NotSendable(
            "The attached preset has no usable settings profile (its reference is unset, or the profile was deleted)."
        )
    }

    if (settings == null) {
        return AgentRoleSendability.NotSendable(
            "The attached preset's settings profile #$effectiveSettingsId is not available (it may have been deleted or is not accessible to you)."
        )
    }

    if (!settings.isChatCapable()) {
        return AgentRoleSendability.NotSendable(
            "The attached preset's settings profile '${settings.name}' is not a chat profile (its model type is ${settings.modelType})."
        )
    }

    if (settings.modelId != effectiveModelId) {
        return AgentRoleSendability.NotSendable(
            "The attached preset's settings profile '${settings.name}' belongs to a different model than the preset."
        )
    }

    return AgentRoleSendability.Sendable
}

/**
 * Whether this settings profile can drive a chat turn.
 *
 * Only `CHAT` (chat completions) and `RESPONSES` (OpenAI Responses API) profiles produce a
 * conversational stream; every other [LLMModelType] exists for a different purpose and is rejected
 * by the server's turn preparation. This mirrors the server-side `isChatLikeSettings` helper.
 *
 * @receiver The settings profile to classify.
 * @return True for a chat-like profile, false otherwise.
 */
internal fun ModelSettings.isChatCapable(): Boolean =
    modelType == LLMModelType.CHAT || modelType == LLMModelType.RESPONSES
