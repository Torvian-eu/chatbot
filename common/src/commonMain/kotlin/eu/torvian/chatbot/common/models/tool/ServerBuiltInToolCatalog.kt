package eu.torvian.chatbot.common.models.tool

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
     * Builds an empty-object input schema (no parameters).
     *
     * A bare `{}` would fail tool validation, which requires a `type` or `properties` key, so the
     * empty shape carries `type: object` plus an empty `properties` map.
     *
     * @return The JSON Schema for a parameterless tool call.
     */
    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    /**
     * Builds a JSON Schema for an integer property.
     *
     * @param description Human-readable description of the property.
     * @param minimum Optional inclusive lower bound, used for row ids so a placeholder `0` is
     *            rejected by the schema instead of reaching the lookup.
     * @return The JSON Schema object for the integer property.
     */
    private fun integerProperty(description: String, minimum: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        if (minimum != null) put("minimum", minimum)
        put("description", description)
    }

    /**
     * Builds a JSON Schema for the instruction-kind property of an authored instruction.
     *
     * The `enum` is derived from [AgentInstructionTypes.allKnown] so the accepted kinds cannot
     * drift from the server's validation of the same value.
     *
     * @return The JSON Schema object for the `type` property.
     */
    private fun instructionTypeProperty(): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", "Kind of the instruction; one of the known instruction kinds.")
        putJsonArray("enum") {
            AgentInstructionTypes.allKnown.forEach { add(it) }
        }
    }

    /**
     * Builds a JSON Schema for a free-form JSON-object property.
     *
     * The object is deliberately open: its keys depend on the instruction kind, and the server
     * validates the kind-specific requirements, so a schema change cannot weaken them.
     *
     * @param description Human-readable description of the property.
     * @return The JSON Schema object for the object-valued property.
     */
    private fun objectProperty(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
    }

    /**
     * Builds a JSON Schema for the text-edit batch of `edit_instruction`.
     *
     * Mirrors the worker `edit_file` tool's `edits` parameter shape: an array of `oldText`/`newText`
     * pairs matched exactly against the original instruction message (array order is not sequential,
     * and all non-overlapping occurrences of each `oldText` are replaced).
     *
     * @return The JSON Schema object for the `edits` array.
     */
    private fun editsProperty(): JsonObject = buildJsonObject {
        put("type", "array")
        put("minItems", 1)
        put(
            "description",
            "Replacement batch matched against the original instruction message; array order is " +
                "not sequential. Each edit replaces all non-overlapping occurrences of its oldText, " +
                "and the operation fails if an oldText matches nothing."
        )
        putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject(OLD_TEXT_PROPERTY) {
                    put("type", "string")
                    put(
                        "description",
                        "Exact text to replace. All non-overlapping occurrences are replaced; add " +
                            "surrounding context to target one occurrence."
                    )
                }
                putJsonObject(NEW_TEXT_PROPERTY) {
                    put("type", "string")
                    put("description", "Replacement text.")
                }
            }
            putJsonArray("required") {
                add(OLD_TEXT_PROPERTY)
                add(NEW_TEXT_PROPERTY)
            }
        }
    }

    /**
     * Builds a JSON Schema for an optional string property.
     *
     * @param description Human-readable description of the property.
     * @return The JSON Schema object for the string property.
     */
    private fun stringProperty(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    /**
     * Builds a JSON Schema for an array-of-integers property.
     *
     * @param description Human-readable description of the property.
     * @return The JSON Schema object for the integer-array property.
     */
    private fun integerArrayProperty(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "integer")
        })
    }

    /**
     * Builds a JSON Schema for the ordered instruction-id list property.
     *
     * A role references instructions by id: the values select existing instruction rows, and their
     * order is the order of the role's instruction list. Instruction content is authored through the
     * instruction surfaces, so the schema carries no nested instruction objects.
     *
     * @param create True when building the schema for `create_agent_role`, false for `update_agent_role`.
     * @return The JSON Schema object for the `instruction_ids` array.
     */
    private fun instructionIdsProperty(create: Boolean): JsonObject = buildJsonObject {
        put("type", "array")
        put(
            "description",
            if (create) {
                "Optional ordered ids of existing instructions that make up the role's system " +
                    "prompt. Every id must reference an instruction owned by the current user, and the " +
                    "same instruction can be linked at most once. Omit to create a role without " +
                    "instructions."
            } else {
                "New ordered ids of the instructions that make up the role's system prompt (full " +
                    "replacement of the role's instruction list). Every id must reference an " +
                    "instruction owned by the current user, and the same instruction can be linked at " +
                    "most once. Omit to keep the role's current instructions."
            }
        )
        put("items", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
        })
    }

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
    val allTools: List<ServerBuiltInToolSpec> = listOf(
        ServerBuiltInToolSpec(
            name = LIST_PROJECTS_NAME,
            description = "Lists all projects owned by the current user, returning each project's id, " +
                "name, description, creation time, and member agent role ids. Use read_project with " +
                "a project id to inspect a single project in detail.",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = READ_PROJECT_NAME,
            description = "Reads one project owned by the current user by its id, returning the full " +
                "project with its name, description, creation time, and member agent role ids.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty("Id of the project to read. The project must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(PROJECT_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = CREATE_PROJECT_NAME,
            description = "Creates a new project owned by the current user. A project is a named " +
                "collection of agent roles; the new project starts empty unless agent_role_ids is " +
                "provided (every listed role must be owned by the current user and must not already " +
                "belong to another project). Returns the created project's full JSON with its id, " +
                "name, description, creation time, and attached member agent role ids.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(NAME_PROPERTY, stringProperty("Unique (per user) project name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("Free-form description of the project."))
                    put(
                        AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty(
                            "Optional agent role ids to attach to the project. Each role must be " +
                                "owned by the current user and must not already belong to another project."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(NAME_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = UPDATE_PROJECT_NAME,
            description = "Updates one project owned by the current user (patch semantics): provide " +
                "only the fields to change; every omitted or null field keeps its persisted value. " +
                "Pass an explicit empty string for description or an empty array for agent_role_ids " +
                "to clear the field; name cannot be cleared (it must stay non-blank and unique per " +
                "user). Returns a concise one-line summary of the operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty("Id of the project to update. The project must be owned by the current user.")
                    )
                    put(NAME_PROPERTY, stringProperty("New unique (per user) project name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("New free-form description of the project."))
                    put(
                        AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty(
                            "New member agent role ids (full replacement of the project's " +
                                "membership). Each role must be owned by the current user and must " +
                                "not already belong to another project."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(PROJECT_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = DELETE_PROJECT_NAME,
            description = "Deletes one project owned by the current user by its id. The project's " +
                "member agent roles are deleted with it, and every instruction row that loses its " +
                "last link through those role deletions is removed as well; instructions still linked " +
                "by a role outside the project survive. Returns a concise one-line summary of the " +
                "operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty("Id of the project to delete. The project must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(PROJECT_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = CLONE_PROJECT_NAME,
            description = "Clones one project owned by the current user: creates a new project under " +
                "the caller-provided name, deep-copying every member agent role of the source as a " +
                "new role row (configuration, tools, spawnable role ids remapped to the clone, and " +
                "the per-user disabled state). The source project and its roles are left untouched. " +
                "Returns the cloned project's full JSON with its id, name, description, creation " +
                "time, and the new member agent role ids.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty("Id of the project to clone. The project must be owned by the current user.")
                    )
                    put(NAME_PROPERTY, stringProperty("Unique (per user) name for the cloned project."))
                    put(
                        DESCRIPTION_PROPERTY,
                        stringProperty(
                            "Optional description of the clone. Omit it to copy the source project's " +
                                "description; pass an explicit value (including an empty string) to override it."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(PROJECT_ID_PROPERTY)
                    add(NAME_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = LIST_AGENT_ROLES_NAME,
            description = "Lists all agent roles owned by the current user, returning each role's id, " +
                "name, display name, description, model preset id, its model id and model settings id " +
                "as resolved from that preset (null when the role has no preset or the preset's " +
                "reference is unset), attached tool ids, " +
                "spawnable role ids, instruction types only, its project id (null when the role is " +
                "unassociated), and its disabled flag (roles disabled by the current user are hidden " +
                "from session selection). Use read_agent_role with a role id to inspect full " +
                "instruction contents.",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = READ_AGENT_ROLE_NAME,
            description = "Reads one agent role owned by the current user by its id, returning the " +
                "full role including its model preset id, the model id and settings id resolved from " +
                "that preset, attached tool ids, spawnable role ids, " +
                "resolved instructions, and its disabled flag for the current user.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        ROLE_ID_PROPERTY,
                        integerProperty("Id of the agent role to read. The role must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(ROLE_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = CREATE_AGENT_ROLE_NAME,
            description = "Creates a new agent role owned by the current user. The role may be " +
                "created without a model preset and completed later via update_agent_role; " +
                "a role without a preset is non-sendable until set. Instructions are referenced " +
                "by the ids of existing instruction rows, never by content: author instruction " +
                "content through the instruction surfaces first. Returns a concise " +
                "one-line summary of the operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        NAME_PROPERTY,
                        stringProperty(
                            "Unique name within the role's project scope for the current user " +
                                "(unassociated roles share a single no-project scope; roles bound to " +
                                "a project must be unique within that project)."
                        )
                    )
                    put(DISPLAY_NAME_PROPERTY, stringProperty("Optional human-friendly display name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("Free-form description of the role."))
                    put(
                        MODEL_PRESET_ID_PROPERTY,
                        integerProperty(
                            "Optional id of the model preset that supplies the role's model and " +
                                "settings profile. The preset must be owned by the current user."
                        )
                    )
                    put(
                        TOOL_IDS_PROPERTY,
                        integerArrayProperty("Optional tool-definition ids to attach to the role. Each tool must be accessible by the current user.")
                    )
                    put(
                        SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty("Optional same-user role ids this role may spawn.")
                    )
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty(
                            "Optional id of the user-owned project the role belongs to. Omit or " +
                                "pass null to create an unassociated role (no project). The project " +
                                "must be owned by the current user."
                        )
                    )
                    put(INSTRUCTION_IDS_PROPERTY, instructionIdsProperty(create = true))
                })
                put("required", buildJsonArray {
                    add(NAME_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = UPDATE_AGENT_ROLE_NAME,
            description = "Updates one agent role owned by the current user (patch semantics): " +
                "provide only the fields to change; every omitted field is preserved, including the " +
                "attached tools, spawnable roles and instructions. Instructions are referenced by " +
                "the ids of existing instruction rows, never by content: author instruction content " +
                "through the instruction surfaces first. Passing null is treated as omitted " +
                "— fields cannot be cleared with null; pass an empty string or an empty array to " +
                "clear a field (project_id is the exception: pass 0 to clear it, which moves the " +
                "role to unassociated; model_preset_id also accepts 0 to detach its preset). " +
                "Returns a concise one-line summary of the operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        ROLE_ID_PROPERTY,
                        integerProperty("Id of the agent role to update. The role must be owned by the current user.")
                    )
                    put(
                        NAME_PROPERTY,
                        stringProperty(
                            "New name for the role. Must be unique within the role's project scope " +
                                "for the current user, like create_agent_role: same-scope collisions " +
                                "are rejected (unassociated roles share one no-project scope)."
                        )
                    )
                    put(DISPLAY_NAME_PROPERTY, stringProperty("New optional human-friendly display name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("New free-form description of the role."))
                    put(
                        MODEL_PRESET_ID_PROPERTY,
                        integerProperty(
                            "New id of the model preset that supplies the role's model and settings " +
                                "profile. The preset must be owned by the current user. When not " +
                                "changing the role's preset, pass the current model_preset_id from " +
                                "read_agent_role or list_agent_roles (an omitted or null value " +
                                "preserves the persisted preset). Pass 0 to explicitly detach the " +
                                "preset (0 is never a valid preset id)."
                        )
                    )
                    put(
                        TOOL_IDS_PROPERTY,
                        integerArrayProperty("New tool-definition ids to attach to the role (full replacement of the role's tool set).")
                    )
                    put(
                        SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty("New same-user role ids this role may spawn (full replacement).")
                    )
                    put(
                        PROJECT_ID_PROPERTY,
                        integerProperty(
                            "Optional id of the user-owned project the role belongs to. The " +
                                "project must be owned by the current user. When not changing the " +
                                "role's project, pass the current project_id from " +
                                "read_agent_role or list_agent_roles (an omitted or null value " +
                                "preserves the persisted project). Pass 0 to explicitly move the " +
                                "role to unassociated (clear the project membership; 0 is never a " +
                                "valid project id)."
                        )
                    )
                    put(INSTRUCTION_IDS_PROPERTY, instructionIdsProperty(create = false))
                })
                put("required", buildJsonArray {
                    add(ROLE_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = DELETE_AGENT_ROLE_NAME,
            description = "Deletes one agent role owned by the current user by its id. Deleting is " +
                "non-destructive for sessions: chat sessions and messages that referenced the role " +
                "keep their history and become inert until another role is selected (no 'role in " +
                "use' rejection applies). Instruction rows that lose their last link through this " +
                "deletion are removed as well; instructions still linked to other roles or assigned " +
                "to no role survive. Returns a concise one-line summary of the operation naming any " +
                "instruction rows removed with the role and any kept because other roles still link " +
                "them.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        ROLE_ID_PROPERTY,
                        integerProperty("Id of the agent role to delete. The role must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(ROLE_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = LIST_INSTRUCTIONS_NAME,
            description = "Lists the instruction library of the current user: every stored " +
                    "instruction with its id, type, name, and the ids of the agent roles that link " +
                    "it. Each row carries no message text, so use read_instruction with an id to " +
                    "fetch the text of one instruction. Pass role_id to report only the instructions " +
                    "assigned to one agent role owned by the current user; an unknown or non-owned " +
                    "role id fails instead of returning an empty list, so an empty result always " +
                    "means that role has no instructions.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        ROLE_ID_PROPERTY,
                        integerProperty(
                            "Optional id of an agent role owned by the current user; when " +
                                    "given, only the instructions assigned to that role are listed.",
                            minimum = 1
                        )
                    )
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = READ_INSTRUCTION_NAME,
            description = "Reads one instruction owned by the current user by its id, " +
                    "returning its full JSON: id, type, name, message, custom data, and the ids of " +
                    "the agent roles that link it. The message is the stored text; for a " +
                    "spawnable_agents instruction it is empty, because that text is generated for " +
                    "each linked role at read time.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        INSTRUCTION_ID_PROPERTY,
                        integerProperty(
                            "Id of the instruction to read. The instruction must be owned by the current user.",
                            minimum = 1
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(INSTRUCTION_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = CREATE_INSTRUCTION_NAME,
            description = "Creates a new instruction owned by the current user. Instructions are " +
                    "standalone library objects that agent roles reference by id; a new row is " +
                    "linked to no role, so assign it in the same turn by passing its id to " +
                    "update_agent_role's instruction_ids. type must name a known instruction kind, " +
                    "name must be a non-blank label of at most 255 characters, message is the " +
                    "instruction text, and custom carries kind-specific data (a model_specific " +
                    "instruction needs custom.modelId). Omit message for a spawnable_agents " +
                    "instruction: its text is generated per linked role, so a supplied message is " +
                    "rejected and the created row reports an empty message. Returns the created " +
                    "instruction's full JSON including its " +
                    "server-generated id.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(TYPE_PROPERTY, instructionTypeProperty())
                    put(
                        NAME_PROPERTY,
                        stringProperty("Human-readable label of the instruction, non-blank and at most 255 characters.")
                    )
                    put(
                        MESSAGE_PROPERTY,
                        stringProperty(
                            "Instruction text. Omit for a spawnable_agents instruction, whose text " +
                                    "is generated for each linked role at read time."
                        )
                    )
                    put(
                        CUSTOM_PROPERTY,
                        objectProperty(
                            "Optional kind-specific data; a model_specific instruction requires " +
                                    "{\"modelId\": <id>} naming the model it applies to."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(TYPE_PROPERTY)
                    add(NAME_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = EDIT_INSTRUCTION_NAME,
            description = "Replaces text inside the message of one instruction owned by the " +
                    "current user. Each edit supplies the exact oldText to replace and its newText; " +
                    "every non-overlapping occurrence of an oldText is replaced, all edits are " +
                    "matched against the original message so array order is not sequential, and the " +
                    "call fails if an oldText matches nothing. Only the message changes: the " +
                    "instruction's type, name and custom data stay as they are, and a " +
                    "spawnable_agents instruction cannot be edited because its text is generated " +
                    "per linked role. The change reaches every agent role that links the " +
                    "instruction, and the result is a plain-text report naming those roles plus a " +
                    "unified diff of the message.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        INSTRUCTION_ID_PROPERTY,
                        integerProperty(
                            "Id of the instruction whose message to edit. The instruction must be " +
                                    "owned by the current user.",
                            minimum = 1
                        )
                    )
                    put(EDITS_PROPERTY, editsProperty())
                })
                put("required", buildJsonArray {
                    add(INSTRUCTION_ID_PROPERTY)
                    add(EDITS_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = DELETE_INSTRUCTION_NAME,
            description = "Deletes one instruction owned by the current user by its id. The call " +
                    "fails with instruction_in_use while any agent role still links the " +
                    "instruction, naming the roles that must unlink it first: remove the link from " +
                    "every role through update_agent_role (deleting the last linking agent role " +
                    "removes the instruction too). Deleting destroys the stored text permanently, " +
                    "whereas unassigning the instruction from every role keeps it in the library " +
                    "instead. Returns a one-line summary naming the deleted instruction.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        INSTRUCTION_ID_PROPERTY,
                        integerProperty(
                            "Id of the instruction to delete. The instruction must be owned by the current user.",
                            minimum = 1
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(INSTRUCTION_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = LIST_MODELS_NAME,
            description = "Lists all LLM models accessible by the current user (models the user owns " +
                "or that are shared with a group the user belongs to).",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = LIST_MODEL_SETTINGS_NAME,
            description = "Lists the settings profiles accessible by the current user for one model " +
                "the user can access. Returns the full settings object including its subtype " +
                "(chat, responses, completion, etc.).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        MODEL_ID_PROPERTY,
                        integerProperty("Id of the model whose settings to list. The model must be accessible by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(MODEL_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = LIST_TOOLS_NAME,
            description = "Lists all tools accessible by the current user (own MCP tools, built-in " +
                "tools of owned workers, own operator tools, and own server built-in tools), " +
                "returning each tool's id, name, description, type, and enabled flag.",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = READ_TOOL_NAME,
            description = "Reads one tool accessible by the current user by its id, returning the " +
                "full tool definition including its subtype-specific fields.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        TOOL_ID_PROPERTY,
                        integerProperty("Id of the tool to read. The tool must be accessible by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(TOOL_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = LIST_MODEL_PRESETS_NAME,
            description = "Lists all model presets owned by the current user, ordered by id, " +
                "returning each preset's id, name, display name, description, referenced model " +
                "id, referenced settings profile id (both null when unset), and its creation and " +
                "update timestamps. A preset bundles one model with one settings profile and is " +
                "the sole source of the LLM configuration of every agent role bound to it. Use " +
                "read_model_preset with a preset id to inspect a single preset.",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = READ_MODEL_PRESET_NAME,
            description = "Reads one model preset owned by the current user by its id, returning " +
                "the full preset with its name, display name, description, the referenced model " +
                "id and settings profile id (null when unset), and its creation and update " +
                "timestamps.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        MODEL_PRESET_ID_PROPERTY,
                        integerProperty("Id of the model preset to read. The preset must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(MODEL_PRESET_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = CREATE_MODEL_PRESET_NAME,
            description = "Creates a new model preset owned by the current user. A model preset is " +
                "a named bundle of one model plus one settings profile and is the sole source of " +
                "the LLM configuration of every agent role bound to it. The name must be unique " +
                "among the current user's presets; the server trims it and rejects a blank or " +
                "longer-than-255-character value. Both references are optional and must be " +
                "accessible by the current user; when both are given, the settings profile must " +
                "belong to the given model. The preset layer imposes no model-type restriction, " +
                "so an embedding model is accepted. Returns the created preset's full JSON " +
                "including its server-generated id and timestamps.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        NAME_PROPERTY,
                        stringProperty("Unique (per user) model preset name, non-blank and at most 255 characters.")
                    )
                    put(DISPLAY_NAME_PROPERTY, stringProperty("Optional human-friendly display name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("Free-form description of the preset's purpose."))
                    put(
                        MODEL_ID_PROPERTY,
                        integerProperty(
                            "Optional id of the model the preset bundles. The model must be " +
                                "accessible by the current user; the preset layer allows any " +
                                "model type (chat, embedding, ...)."
                        )
                    )
                    put(
                        MODEL_SETTINGS_ID_PROPERTY,
                        integerProperty(
                            "Optional id of the settings profile the preset bundles. The " +
                                "profile must be accessible by the current user and, when " +
                                "model_id is also given, must belong to that model."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(NAME_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = UPDATE_MODEL_PRESET_NAME,
            description = "Updates one model preset owned by the current user (patch semantics): " +
                "provide only the fields to change; every omitted or null field keeps its " +
                "persisted value. Pass an explicit empty string to clear description or " +
                "display_name, or pass 0 for model_id or model_settings_id to clear that " +
                "reference (0 is never a valid id). A name cannot be cleared (it must stay " +
                "non-blank and " +
                "unique per user). Both references must be accessible by the current user and, " +
                "when both are set, the settings profile must belong to the model, so re-point " +
                "both together. Returns a concise one-line summary of the operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        MODEL_PRESET_ID_PROPERTY,
                        integerProperty("Id of the model preset to update. The preset must be owned by the current user.")
                    )
                    put(
                        NAME_PROPERTY,
                        stringProperty("New unique (per user) preset name; a name cannot be cleared.")
                    )
                    put(
                        DISPLAY_NAME_PROPERTY,
                        stringProperty(
                            "New optional human-friendly display name; an explicit empty " +
                                "string clears it, an omitted or null value keeps the persisted one."
                        )
                    )
                    put(
                        DESCRIPTION_PROPERTY,
                        stringProperty(
                            "New free-form description; an explicit empty string clears it, an " +
                                "omitted or null value keeps the persisted one."
                        )
                    )
                    put(
                        MODEL_ID_PROPERTY,
                        integerProperty(
                            "New id of the model the preset bundles. Omit or pass null to keep " +
                                "the persisted model; pass 0 to clear the reference (0 is never a " +
                                "valid model id). The model must be accessible by the current user."
                        )
                    )
                    put(
                        MODEL_SETTINGS_ID_PROPERTY,
                        integerProperty(
                            "New id of the settings profile the preset bundles. Omit or pass " +
                                "null to keep the persisted profile; pass 0 to clear the reference " +
                                "(0 is never a valid settings id). The profile must be accessible " +
                                "by the current user and, when model_id is also set, must belong " +
                                "to that model."
                        )
                    )
                })
                put("required", buildJsonArray {
                    add(MODEL_PRESET_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = DELETE_MODEL_PRESET_NAME,
            description = "Deletes one model preset owned by the current user by its id. Agent " +
                "roles bound to the preset are not deleted: they lose the reference and become " +
                "non-sendable until another preset is attached. Returns a concise one-line " +
                "summary of the operation.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        MODEL_PRESET_ID_PROPERTY,
                        integerProperty("Id of the model preset to delete. The preset must be owned by the current user.")
                    )
                })
                put("required", buildJsonArray {
                    add(MODEL_PRESET_ID_PROPERTY)
                })
            }
        ),
        ServerBuiltInToolSpec(
            name = GET_CURRENT_SESSION_INFO_NAME,
            description = "Returns the current chat session's id and name together with the id, " +
                    "name, and (when set) display name of the agent role selected for that session, " +
                    "and the session's project id (present only when the session has a project " +
                    "selected). The session is the one the current conversation belongs to and is " +
                    "always owned by the current user, so no other user's data is ever exposed.",
            inputSchema = emptyObjectSchema()
        )
    )
}
