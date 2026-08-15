package eu.torvian.chatbot.common.models.agent

/**
 * Centralized constants for the well-known agent-instruction type keys.
 *
 * An agent role's `instructions` list is a flat, type-tagged list of [AgentInstructionDto] entries.
 * The `type` field of each entry is one of these well-known keys. Any other string is tolerated as an
 * unknown/custom type: the client renders such entries generically and the server maps known keys to
 * domain instruction subtypes (see the server-side `AgentInstruction` hierarchy).
 *
 * This mirrors the constants-object pattern used by `CommonUserGroups` and `CommonPermissions` so that
 * instruction-type strings stay typo-free and centralized across the shared API surface.
 */
object AgentInstructionTypes {

    /**
     * Static role description, e.g. "You are a senior software architect...".
     */
    const val ROLE: String = "role"

    /**
     * Project context, usually `AGENTS.md` content.
     */
    const val MAIN: String = "main"

    /**
     * References the role's `ModelSettings`; the message text is resolved server-side from the
     * settings profile (`ChatModelSettings.systemMessage` / `ResponsesModelSettings.instructions`).
     * This entry is not directly editable by the client: the server binds it to the role's own
     * `modelSettingsId` and re-resolves its `message` on every read.
     */
    const val MODEL_SETTINGS: String = "model_settings"

    /**
     * User-editable free text.
     */
    const val CUSTOM: String = "custom"

    // Future instruction kinds (spawnable agents, skills) will be added as new constants here,
    // e.g. SPAWNABLE_AGENTS = "spawnable_agents", SKILLS = "skills". No new DTO types are needed.
}
