package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.AGENT_ROLE_IDS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CLONE_PROJECT_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CREATE_PROJECT_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DELETE_PROJECT_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_PROJECTS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.READ_PROJECT_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.UPDATE_PROJECT_NAME
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Project management tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val projectToolSpecs: List<ServerBuiltInToolSpec> = listOf(
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
)
