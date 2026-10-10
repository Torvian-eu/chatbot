package eu.torvian.chatbot.common.models.tool

import eu.torvian.chatbot.common.models.tool.specs.agentRoleToolSpecs
import eu.torvian.chatbot.common.models.tool.specs.instructionToolSpecs
import eu.torvian.chatbot.common.models.tool.specs.modelPresetToolSpecs
import eu.torvian.chatbot.common.models.tool.specs.modelToolSpecs
import eu.torvian.chatbot.common.models.tool.specs.projectToolSpecs
import eu.torvian.chatbot.common.models.tool.specs.sessionToolSpecs
import kotlinx.serialization.json.JsonObject

/**
 * Canonical catalog of server built-in tools.
 *
 * This is the single source of truth for the public metadata (name, description, and input JSON
 * Schema) of every server built-in tool. The server seeds **one `tool_definitions` row per user**
 * from these specs (see the server-side `ServerBuiltInToolDefinitionSeeder`), so each user gets
 * their own instances with user-scoped approval preferences and enable/disable flags.
 *
 * The catalog describes the **kind** (e.g. `list_agent_roles`): name, description, and input
 * schema. It is deliberately not a row — the seeder instantiates per-user instances from a spec.
 * The spec names are **canonical** (unprefixed). The public/LLM-facing name of an instance is the
 * user's configured tool-name prefix concatenated to the canonical name (e.g.
 * `chatbot-list_agent_roles` for the default prefix `"chatbot-"`), applied per user by the
 * server-side seeder; blank prefix means the canonical name itself. The canonical name is what the
 * executor dispatches on (persisted as `ServerBuiltInToolDefinition.builtInToolName`), never the
 * prefixed public name.
 *
 * All v1 tools are read/manage operations on agent-role, model, model-settings, tool, and project
 * objects, each strictly user-scoped. Descriptions state that scoping explicitly so the LLM can
 * self-correct on authorization errors instead of treating them as global resources.
 */
object ServerBuiltInToolCatalog {

    /** Canonical, unprefixed catalog name of the `list_agent_roles` tool. */
    const val LIST_AGENT_ROLES_NAME = "list_agent_roles"

    /** Canonical, unprefixed catalog name of the `read_agent_role` tool. */
    const val READ_AGENT_ROLE_NAME = "read_agent_role"

    /** Canonical, unprefixed catalog name of the `create_agent_role` tool. */
    const val CREATE_AGENT_ROLE_NAME = "create_agent_role"

    /** Canonical, unprefixed catalog name of the `update_agent_role` tool. */
    const val UPDATE_AGENT_ROLE_NAME = "update_agent_role"

    /** Canonical, unprefixed catalog name of the `delete_agent_role` tool. */
    const val DELETE_AGENT_ROLE_NAME = "delete_agent_role"

    /** Canonical, unprefixed catalog name of the `list_models` tool. */
    const val LIST_MODELS_NAME = "list_models"

    /** Canonical, unprefixed catalog name of the `list_model_settings` tool. */
    const val LIST_MODEL_SETTINGS_NAME = "list_model_settings"

    /** Canonical, unprefixed catalog name of the `list_tools` tool. */
    const val LIST_TOOLS_NAME = "list_tools"

    /** Canonical, unprefixed catalog name of the `read_tool` tool. */
    const val READ_TOOL_NAME = "read_tool"

    /** JSON property holding the agent-role id for role read/update calls. */
    const val ROLE_ID_PROPERTY = "role_id"

    /** JSON property holding the tool-definition id for the `read_tool` call. */
    const val TOOL_ID_PROPERTY = "tool_id"

    /** JSON property holding the LLM model id. Used by `list_model_settings` and by the
     * model-preset tools (`create_model_preset`/`update_model_preset`); the agent-role tools use
     * [MODEL_PRESET_ID_PROPERTY] instead. */
    const val MODEL_ID_PROPERTY = "model_id"

    /**
     * JSON property holding the model-settings-profile id. Used exclusively by the model-preset
     * tools (`create_model_preset`/`update_model_preset`): a preset bundles one model with one
     * settings profile, and the agent-role tools never accept a settings id directly (they use
     * [MODEL_PRESET_ID_PROPERTY] and resolve the profile through the preset).
     */
    const val MODEL_SETTINGS_ID_PROPERTY = "model_settings_id"

    /** JSON property holding the model-preset id: the preset a role uses (create/update role) and
     * the preset addressed by the read/update/delete preset tools. */
    const val MODEL_PRESET_ID_PROPERTY = "model_preset_id"

    /** JSON property holding the role name (create/update role). */
    const val NAME_PROPERTY = "name"

    /** JSON property holding the optional human-friendly display name. */
    const val DISPLAY_NAME_PROPERTY = "display_name"

    /** JSON property holding the free-form role description. */
    const val DESCRIPTION_PROPERTY = "description"

    /**
     * JSON property holding a model preset's automatic-compaction flag (create/update preset). When
     * `false` the preset's sessions never compact automatically, regardless of the user's
     * `conversation_compaction` preference. User-requested compaction is unaffected.
     */
    const val AUTOMATIC_COMPACTION_ENABLED_PROPERTY = "automatic_compaction_enabled"

    /**
     * JSON property holding a model preset's optional compaction threshold in input tokens
     * (create/update preset). Omitted or `null` means "use the user preference threshold"; on update,
     * `0` is the sentinel that clears a stored value back to that fallback.
     */
    const val COMPACTION_THRESHOLD_TOKENS_PROPERTY = "compaction_threshold_tokens"

    /** JSON property holding the tool-definition ids attached to a role. */
    const val TOOL_IDS_PROPERTY = "tool_ids"

    /** JSON property holding the spawn allow-list (role ids a role may spawn). */
    const val SPAWNABLE_AGENT_ROLE_IDS_PROPERTY = "spawnable_agent_role_ids"

    /** JSON property holding the id of the user-owned project a role belongs to (create/update
     * role; absent or null means an unassociated role). */
    const val PROJECT_ID_PROPERTY = "project_id"

    /** JSON property holding the ordered instruction row ids a role links. */
    const val INSTRUCTION_IDS_PROPERTY = "instruction_ids"

    /** Canonical, unprefixed catalog name of the `get_current_session_info` tool. */
    const val GET_CURRENT_SESSION_INFO_NAME = "get_current_session_info"

    /** Canonical, unprefixed catalog name of the `list_projects` tool. */
    const val LIST_PROJECTS_NAME = "list_projects"

    /** Canonical, unprefixed catalog name of the `read_project` tool. */
    const val READ_PROJECT_NAME = "read_project"

    /** Canonical, unprefixed catalog name of the `create_project` tool. */
    const val CREATE_PROJECT_NAME = "create_project"

    /** Canonical, unprefixed catalog name of the `update_project` tool. */
    const val UPDATE_PROJECT_NAME = "update_project"

    /** Canonical, unprefixed catalog name of the `delete_project` tool. */
    const val DELETE_PROJECT_NAME = "delete_project"

    /** Canonical, unprefixed catalog name of the `clone_project` tool. */
    const val CLONE_PROJECT_NAME = "clone_project"

    /** Canonical, unprefixed catalog name of the `list_model_presets` tool. */
    const val LIST_MODEL_PRESETS_NAME = "list_model_presets"

    /** Canonical, unprefixed catalog name of the `read_model_preset` tool. */
    const val READ_MODEL_PRESET_NAME = "read_model_preset"

    /** Canonical, unprefixed catalog name of the `create_model_preset` tool. */
    const val CREATE_MODEL_PRESET_NAME = "create_model_preset"

    /** Canonical, unprefixed catalog name of the `update_model_preset` tool. */
    const val UPDATE_MODEL_PRESET_NAME = "update_model_preset"

    /** Canonical, unprefixed catalog name of the `delete_model_preset` tool. */
    const val DELETE_MODEL_PRESET_NAME = "delete_model_preset"

    /** JSON property holding the user-owned agent-role ids attached to a project. */
    const val AGENT_ROLE_IDS_PROPERTY = "agent_role_ids"

    /** Canonical, unprefixed catalog name of the `list_instructions` tool. */
    const val LIST_INSTRUCTIONS_NAME = "list_instructions"

    /** Canonical, unprefixed catalog name of the `read_instruction` tool. */
    const val READ_INSTRUCTION_NAME = "read_instruction"

    /** Canonical, unprefixed catalog name of the `create_instruction` tool. */
    const val CREATE_INSTRUCTION_NAME = "create_instruction"

    /** Canonical, unprefixed catalog name of the `edit_instruction` tool. */
    const val EDIT_INSTRUCTION_NAME = "edit_instruction"

    /** Canonical, unprefixed catalog name of the `delete_instruction` tool. */
    const val DELETE_INSTRUCTION_NAME = "delete_instruction"

    /** JSON property holding the instruction-row id addressed by the instruction tools. */
    const val INSTRUCTION_ID_PROPERTY = "instruction_id"

    /** JSON property holding the instruction kind key of an authored instruction. */
    const val TYPE_PROPERTY = "type"

    /** JSON property holding the instruction text of an authored instruction. */
    const val MESSAGE_PROPERTY = "message"

    /** JSON property holding the kind-specific extra fields of an authored instruction. */
    const val CUSTOM_PROPERTY = "custom"

    /** JSON property holding the text-edit batch of `edit_instruction`. */
    const val EDITS_PROPERTY = "edits"

    /** JSON property inside each [EDITS_PROPERTY] item: the exact text to be replaced. */
    const val OLD_TEXT_PROPERTY = "oldText"

    /** JSON property inside each [EDITS_PROPERTY] item: the replacement text. */
    const val NEW_TEXT_PROPERTY = "newText"

    /**
     * Immutable specification of a single server built-in tool.
     *
     * @property name Canonical, unprefixed catalog name (e.g. `list_agent_roles`). The public
     *            LLM-facing name is the user's prefix concatenated to this name, applied per user
     *            by the seeder; the canonical name is the executor dispatch key.
     * @property description Human-readable description surfaced to the LLM, stating user-scoping.
     * @property inputSchema JSON Schema describing the tool's expected input arguments.
     */
    data class ServerBuiltInToolSpec(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    /**
     * Returns the catalog spec for the given canonical tool name.
     *
     * @param name The canonical, unprefixed catalog name (e.g. [LIST_AGENT_ROLES_NAME]).
     * @return The matching [ServerBuiltInToolSpec], or null when the name is unknown.
     */
    fun specFor(name: String): ServerBuiltInToolSpec? = allTools.firstOrNull { it.name == name }

    /**
     * All server built-in tool specifications, in stable catalog order.
     *
     * The order is part of the contract: it defines the seeding order and the order in which tools
     * appear in listings. Do not reorder entries.
     */
    val allTools: List<ServerBuiltInToolSpec> =
        projectToolSpecs + agentRoleToolSpecs + instructionToolSpecs + modelToolSpecs +
            modelPresetToolSpecs + sessionToolSpecs
}
