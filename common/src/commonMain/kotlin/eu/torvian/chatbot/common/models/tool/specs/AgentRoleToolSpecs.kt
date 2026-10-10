package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CREATE_AGENT_ROLE_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DELETE_AGENT_ROLE_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.INSTRUCTION_IDS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.READ_AGENT_ROLE_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ROLE_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.SPAWNABLE_AGENT_ROLE_IDS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.TOOL_IDS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.UPDATE_AGENT_ROLE_NAME
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent role tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val agentRoleToolSpecs: List<ServerBuiltInToolSpec> = listOf(
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
)
