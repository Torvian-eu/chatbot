package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable

/**
 * Flat, serializable wire contract for a single agent-role instruction.
 *
 * This is the only shape of an instruction that crosses the API boundary or is persisted in the
 * `agent_roles.instructions_json` column; there is deliberately no polymorphic hierarchy here. The
 * server maps each entry to its server-side domain subtype (see the server `AgentInstruction`
 * interface) and always resolves [message] before composing a DTO, so `message` is non-null on the
 * wire and in storage.
 *
 * @property type Well-known [AgentInstructionTypes] key (e.g. `"role"`, `"main"`, `"model_settings"`,
 *            `"custom"`). Unknown strings are tolerated and rendered generically by clients.
 * @property name Human-readable label of the instruction.
 * @property message Resolved instruction text. For [AgentInstructionTypes.MODEL_SETTINGS] the server
 *            sends the referenced settings' system text; for static instruction kinds the text is the
 *            stored value itself.
 */
@Serializable
data class AgentInstructionDto(
    val type: String,
    val name: String,
    val message: String
)
