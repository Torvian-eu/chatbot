package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Pure server-side domain type for a single agent-role instruction.
 *
 * This interface is deliberately NOT `@Serializable` / `@Polymorphic`: the only shape that crosses the
 * wire or is persisted is the flat [eu.torvian.chatbot.common.models.agent.AgentInstructionDto]. This
 * interface exists purely for behavior — namely [loadMessage], which resolves the instruction's message
 * from its source (the database for DB-backed variants like [ModelSettingsInstruction]).
 *
 * @property type Well-known [AgentInstructionTypes] key that drives DTO mapping.
 * @property name Human-readable label of the instruction.
 * @property message Resolved instruction text. May be empty until [loadMessage] has been invoked.
 */
interface AgentInstruction {
    /** Instruction kind; drives the DTO mapping. */
    val type: String

    /** Human-readable label of the instruction. */
    val name: String

    /** Resolved instruction text. */
    val message: String

    /**
     * Loads the instruction's message from its source into [message].
     *
     * Static instruction kinds ([RoleInstruction], [MainInstruction], [CustomInstruction]) already
     * carry their message, so this is a no-op for them. [ModelSettingsInstruction] resolves the
     * referenced settings' system text from the database.
     */
    suspend fun loadMessage()
}

/**
 * Static role description, e.g. "You are a senior software architect...".
 *
 * @property name Human-readable label of the instruction.
 * @property message The role description text (already populated).
 */
data class RoleInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.ROLE

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}

/**
 * Project context, usually `AGENTS.md` content.
 *
 * @property name Human-readable label of the instruction.
 * @property message The project context text (already populated).
 */
data class MainInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.MAIN

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}

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

/**
 * User-editable free text.
 *
 * @property name Human-readable label of the instruction.
 * @property message The custom instruction text (already populated).
 */
data class CustomInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.CUSTOM

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}

// Future instruction kinds (SpawnableAgentsInstruction, SkillsInstruction) will be added as new
// subtypes here; they are dynamic and their message is populated by loadMessage().
