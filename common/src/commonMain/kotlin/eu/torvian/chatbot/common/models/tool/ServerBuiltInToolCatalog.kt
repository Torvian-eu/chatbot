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
 * Because there is exactly one server, the public name of a server built-in tool equals its
 * canonical catalog name and is unique within a user's tool set; the name doubles as the executor
 * dispatch key used by the server-side `DefaultServerBuiltInToolExecutor`.
 *
 * All v1 tools are read/manage operations on agent-role, model, model-settings, and tool objects,
 * each strictly user-scoped. Descriptions state that scoping explicitly so the LLM can self-correct
 * on authorization errors instead of treating them as global resources.
 */
object ServerBuiltInToolCatalog {

    /** Public, LLM-facing name of the `list_agent_roles` tool. */
    const val LIST_AGENT_ROLES_NAME = "list_agent_roles"

    /** Public, LLM-facing name of the `read_agent_role` tool. */
    const val READ_AGENT_ROLE_NAME = "read_agent_role"

    /** Public, LLM-facing name of the `create_agent_role` tool. */
    const val CREATE_AGENT_ROLE_NAME = "create_agent_role"

    /** Public, LLM-facing name of the `update_agent_role` tool. */
    const val UPDATE_AGENT_ROLE_NAME = "update_agent_role"

    /** Public, LLM-facing name of the `list_models` tool. */
    const val LIST_MODELS_NAME = "list_models"

    /** Public, LLM-facing name of the `list_model_settings` tool. */
    const val LIST_MODEL_SETTINGS_NAME = "list_model_settings"

    /** Public, LLM-facing name of the `list_tools` tool. */
    const val LIST_TOOLS_NAME = "list_tools"

    /** Public, LLM-facing name of the `read_tool` tool. */
    const val READ_TOOL_NAME = "read_tool"

    /** JSON property holding the agent-role id for role read/update calls. */
    const val ROLE_ID_PROPERTY = "role_id"

    /** JSON property holding the tool-definition id for the `read_tool` call. */
    const val TOOL_ID_PROPERTY = "tool_id"

    /** JSON property holding the LLM model id (create/update role, list_model_settings). */
    const val MODEL_ID_PROPERTY = "model_id"

    /** JSON property holding the model-settings profile id (create/update role). */
    const val MODEL_SETTINGS_ID_PROPERTY = "model_settings_id"

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

    /** JSON property holding the flat instruction list of a role (advanced; see [AgentInstructionDto]). */
    const val INSTRUCTIONS_PROPERTY = "instructions"

    /**
     * Immutable specification of a single server built-in tool.
     *
     * @property name Public tool name exposed to the LLM; also the executor dispatch key.
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
     * @return The JSON Schema object for the integer property.
     */
    private fun integerProperty(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
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
     * Builds a JSON Schema for the optional instruction list (advanced usage), with the nested
     * item shape fully defined per the JSON Schema specification.
     *
     * The `items` entry is itself an object whose `properties` recursively describe one
     * [AgentInstructionDto]: `type` (enum of [AgentInstructionTypes]), `name`, `message`, and the
     * nullable nested `custom` object carrying `modelId` for `model_specific` instructions. The
     * schema documents the shape but cannot enforce the per-type business rules (e.g.
     * `model_specific` requires `custom.modelId`, other kinds require `custom == null`); those are
     * validated server-side by the role service, which is more reliable than `if`/`then` for LLM
     * function-calling providers that only support a JSON Schema subset.
     *
     * @return The JSON Schema object for the `instructions` array.
     */
    private fun instructionsProperty(): JsonObject = buildJsonObject {
        put("type", "array")
        put(
            "description",
            """
            Ordered list of instructions used to compose the agent role's system prompt.

            Supported instruction types:
            - role: static description of the agent's role
            - main: project or AGENTS.md context
            - custom: user-editable free-text instruction
            - spawnable_agents: server-generated guidance; the supplied message is ignored
            - model_specific: instruction applied only to custom.modelId
            """.trimIndent()
        )

        putJsonObject("items") {
            put("type", "object")
            put("additionalProperties", false)

            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "The instruction kind.")

                    putJsonArray("enum") {
                        add(AgentInstructionTypes.ROLE)
                        add(AgentInstructionTypes.MAIN)
                        add(AgentInstructionTypes.CUSTOM)
                        add(AgentInstructionTypes.SPAWNABLE_AGENTS)
                        add(AgentInstructionTypes.MODEL_SPECIFIC)
                    }
                }

                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Human-readable label for the instruction.")
                }

                putJsonObject("message") {
                    put("type", "string")
                    put(
                        "description",
                        """
                        Instruction text. For spawnable_agents the server ignores this value and
                        generates the message from the role's spawn allow-list.
                        """.trimIndent()
                    )
                }

                putJsonObject("custom") {
                    // Nullable nested object; the union type must be expressed as an array so the
                    // LLM can either omit the value (null) or pass an object.
                    putJsonArray("type") {
                        add("object")
                        add("null")
                    }

                    put(
                        "description",
                        """
                        Type-specific data. For model_specific this must contain modelId; for the
                        other currently supported instruction types it should be null.
                        """.trimIndent()
                    )

                    putJsonObject("properties") {
                        putJsonObject("modelId") {
                            put("type", "integer")
                            put("minimum", 1)
                            put(
                                "description",
                                "ID of the model targeted by a model_specific instruction."
                            )
                        }
                    }

                    put("additionalProperties", false)
                }
            }

            putJsonArray("required") {
                add("type")
                add("name")
                add("message")
                add("custom")
            }
        }
    }

    /**
     * Returns the catalog spec for the given public tool name.
     *
     * @param name The public tool name (e.g. [LIST_AGENT_ROLES_NAME]).
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
            name = LIST_AGENT_ROLES_NAME,
            description = "Lists all agent roles owned by the current user, returning each role's " +
                "id, name, display name, and description.",
            inputSchema = emptyObjectSchema()
        ),
        ServerBuiltInToolSpec(
            name = READ_AGENT_ROLE_NAME,
            description = "Reads one agent role owned by the current user by its id, returning the " +
                "full role including its model/settings ids, attached tool ids, spawnable role ids " +
                "and resolved instructions.",
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
                "created without a model and settings and completed later via update_agent_role; " +
                "a role without a model/settings is non-sendable until set.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(NAME_PROPERTY, stringProperty("Unique (per user) machine-readable role name."))
                    put(DISPLAY_NAME_PROPERTY, stringProperty("Optional human-friendly display name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("Free-form description of the role."))
                    put(
                        MODEL_ID_PROPERTY,
                        integerProperty("Optional id of the LLM model the role uses. Must be accessible by the current user.")
                    )
                    put(
                        MODEL_SETTINGS_ID_PROPERTY,
                        integerProperty("Optional id of the settings profile the role uses. Must belong to the model and be chat-capable.")
                    )
                    put(
                        TOOL_IDS_PROPERTY,
                        integerArrayProperty("Optional tool-definition ids to attach to the role. Each tool must be accessible by the current user.")
                    )
                    put(
                        SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty("Optional same-user role ids this role may spawn.")
                    )
                    put(INSTRUCTIONS_PROPERTY, instructionsProperty())
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
                "attached tools, spawnable roles and instructions. Passing null is treated as omitted " +
                "— fields cannot be cleared with null; pass an empty string or an empty array to " +
                "clear a field.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(
                        ROLE_ID_PROPERTY,
                        integerProperty("Id of the agent role to update. The role must be owned by the current user.")
                    )
                    put(NAME_PROPERTY, stringProperty("New unique (per user) machine-readable role name."))
                    put(DISPLAY_NAME_PROPERTY, stringProperty("New optional human-friendly display name."))
                    put(DESCRIPTION_PROPERTY, stringProperty("New free-form description of the role."))
                    put(
                        MODEL_ID_PROPERTY,
                        integerProperty("New id of the LLM model the role uses. Must be accessible by the current user.")
                    )
                    put(
                        MODEL_SETTINGS_ID_PROPERTY,
                        integerProperty("New id of the settings profile the role uses. Must belong to the model and be chat-capable.")
                    )
                    put(
                        TOOL_IDS_PROPERTY,
                        integerArrayProperty("New tool-definition ids to attach to the role (full replacement of the role's tool set).")
                    )
                    put(
                        SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                        integerArrayProperty("New same-user role ids this role may spawn (full replacement).")
                    )
                    put(INSTRUCTIONS_PROPERTY, instructionsProperty())
                })
                put("required", buildJsonArray {
                    add(ROLE_ID_PROPERTY)
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
        )
    )
}
