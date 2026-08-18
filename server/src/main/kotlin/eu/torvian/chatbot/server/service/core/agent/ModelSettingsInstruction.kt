package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * References the role's [eu.torvian.chatbot.common.models.llm.ModelSettings]; the message is resolved
 * server-side from the referenced settings profile (`ChatModelSettings.systemMessage` /
 * `ResponsesModelSettings.instructions`) and cached in-memory by [loadMessage].
 *
 * This variant carries no text of its own: the client never edits it directly, and the server binds it
 * to the role's `modelSettingsId` when reconstructing the domain type from a DTO.
 *
 * @property name Human-readable label of the instruction.
 * @property modelSettingsId Identifier of the settings profile whose system text is resolved.
 * @property messageLoader Suspended resolver that turns a settings id into its system text. Kept as a
 *            constructor-injected function so the domain type stays free of DAO dependencies.
 */
data class ModelSettingsInstruction(
    override val name: String,
    val modelSettingsId: Long,
    private val messageLoader: suspend (Long) -> String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.MODEL_SETTINGS

    private var _message: String? = null

    override val message: String get() = _message ?: ""

    override suspend fun loadMessage() {
        if (_message == null) {
            _message = messageLoader(modelSettingsId)
        }
    }
}