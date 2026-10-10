package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CREATE_INSTRUCTION_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CUSTOM_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DELETE_INSTRUCTION_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.EDITS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.EDIT_INSTRUCTION_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_INSTRUCTIONS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MESSAGE_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.READ_INSTRUCTION_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ROLE_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.TYPE_PROPERTY
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instruction tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val instructionToolSpecs: List<ServerBuiltInToolSpec> = listOf(
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
)
