package eu.torvian.chatbot.common.models.tool

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Serialization round-trip tests for the server built-in tool wire contract.
 *
 * Verifies that the new shared types survive a JSON encode → decode cycle with the default shared
 * `Json` codec, including the sealed-interface discriminators used by the WebSocket protocol.
 */
class ServerBuiltInToolSerializationTest {

    private val json = Json

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /**
     * Verifies the `instruction_ids` input schema declares an ordered array of positive integers.
     */
    @Test
    fun `instruction_ids schema declares an ordered array of positive integers`() {
        val createSpec = ServerBuiltInToolCatalog.specFor(ServerBuiltInToolCatalog.CREATE_AGENT_ROLE_NAME)!!
        val properties = createSpec.inputSchema["properties"]!!.jsonObject
        val instructionIds = properties[ServerBuiltInToolCatalog.INSTRUCTION_IDS_PROPERTY]!!.jsonObject
        assertEquals("array", instructionIds["type"]?.jsonPrimitive?.content)

        val items = instructionIds["items"]!!.jsonObject
        assertEquals("integer", items["type"]?.jsonPrimitive?.content)
        assertEquals(1L, items["minimum"]?.jsonPrimitive?.content?.toLong())

        // The list is optional: a role may be created without instructions.
        val required = createSpec.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(ServerBuiltInToolCatalog.NAME_PROPERTY), required)
    }

    /**
     * Verifies that a [ServerBuiltInToolDefinition] round-trips with the `tool_type` discriminator
     * and carries the required `builtInToolName` wire property.
     */
    @Test
    fun `ServerBuiltInToolDefinition round-trips with tool_type discriminator`() {
        val definition = ServerBuiltInToolDefinition(
            id = 21L,
            name = "chatbot-" + ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
            description = "Lists agent roles",
            config = buildJsonObject { },
            inputSchema = ServerBuiltInToolCatalog.allTools.first().inputSchema,
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            userId = 9L,
            builtInToolName = ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME
        )

        val encoded = json.encodeToString(ServerBuiltInToolDefinition.serializer(), definition)
        val decoded = json.decodeFromString(ServerBuiltInToolDefinition.serializer(), encoded)

        assertEquals(definition, decoded)
        assertEquals(ToolType.BUILTIN_SERVER, decoded.type)
        // The wire shape must carry the canonical, unprefixed built-in name next to the public name.
        assertTrue(encoded.contains("\"builtInToolName\":\"${ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME}\""))
        assertEquals(ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME, decoded.builtInToolName)
    }

    /**
     * Verifies that the polymorphic [ToolDefinition] surface includes the server built-in subtype.
     */
    @Test
    fun `ServerBuiltInToolDefinition round-trips through the sealed ToolDefinition surface`() {
        val definition: ToolDefinition = ServerBuiltInToolDefinition(
            id = 22L,
            name = "chatbot-" + ServerBuiltInToolCatalog.READ_AGENT_ROLE_NAME,
            description = "Reads one agent role",
            config = buildJsonObject { },
            inputSchema = ServerBuiltInToolCatalog.allTools[1].inputSchema,
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            userId = 9L,
            builtInToolName = ServerBuiltInToolCatalog.READ_AGENT_ROLE_NAME
        )

        val encoded = json.encodeToString(ToolDefinition.serializer(), definition)
        val decoded = json.decodeFromString(ToolDefinition.serializer(), encoded)

        assertEquals(definition, decoded)
        assertEquals(ToolType.BUILTIN_SERVER, decoded.type)
        // The sealed-surface serializer uses the default class discriminator (same contract as the
        // existing REST tool routes); the concrete-type serializer uses 'tool_type' instead.
        assertTrue(encoded.contains("\"type\""))
        assertTrue(encoded.contains("ServerBuiltInToolDefinition"))
    }

    /**
     * Verifies that [ToolSummary] round-trips.
     */
    @Test
    fun `ToolSummary round-trips`() {
        val summary = ToolSummary(
            id = 5L,
            name = "list_models",
            description = "Lists accessible models",
            type = ToolType.BUILTIN_SERVER,
            isEnabled = true
        )

        val encoded = json.encodeToString(ToolSummary.serializer(), summary)
        val decoded = json.decodeFromString(ToolSummary.serializer(), encoded)

        assertEquals(summary, decoded)
    }

    /**
     * Verifies that [ChatClientEvent.ServerBuiltInToolCallApproval] round-trips with its
     * `@SerialName` discriminator on the sealed [ChatClientEvent] interface.
     */
    @Test
    fun `ChatClientEvent ServerBuiltInToolCallApproval round-trips`() {
        val approval = ChatClientEvent.ServerBuiltInToolCallApproval(
            toolCallId = 1L,
            approved = true,
            denialReason = null
        )
        val encoded = json.encodeToString(ChatClientEvent.serializer(), approval)
        assertEquals(approval, json.decodeFromString(ChatClientEvent.serializer(), encoded))
        assertTrue(encoded.contains("server_builtin_tool_call_approval"))
    }

    /**
     * Verifies the catalog defines every tool in stable order (the six project tools, the
     * get_current_session_info session-identity tool, the remaining role/model/settings/tool tools,
     * the delete_agent_role delete tool, the five model-preset tools, and the five
     * instruction-library tools) and that
     * every parameterless schema passes the empty-object shape (so seeding validation never
     * rejects it).
     */
    @Test
    fun `catalog defines every tool in stable order`() {
        val names = ServerBuiltInToolCatalog.allTools.map { it.name }
        assertEquals(
            listOf(
                ServerBuiltInToolCatalog.LIST_PROJECTS_NAME,
                ServerBuiltInToolCatalog.READ_PROJECT_NAME,
                ServerBuiltInToolCatalog.CREATE_PROJECT_NAME,
                ServerBuiltInToolCatalog.UPDATE_PROJECT_NAME,
                ServerBuiltInToolCatalog.DELETE_PROJECT_NAME,
                ServerBuiltInToolCatalog.CLONE_PROJECT_NAME,
                ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
                ServerBuiltInToolCatalog.READ_AGENT_ROLE_NAME,
                ServerBuiltInToolCatalog.CREATE_AGENT_ROLE_NAME,
                ServerBuiltInToolCatalog.UPDATE_AGENT_ROLE_NAME,
                ServerBuiltInToolCatalog.DELETE_AGENT_ROLE_NAME,
                ServerBuiltInToolCatalog.LIST_INSTRUCTIONS_NAME,
                ServerBuiltInToolCatalog.READ_INSTRUCTION_NAME,
                ServerBuiltInToolCatalog.CREATE_INSTRUCTION_NAME,
                ServerBuiltInToolCatalog.EDIT_INSTRUCTION_NAME,
                ServerBuiltInToolCatalog.DELETE_INSTRUCTION_NAME,
                ServerBuiltInToolCatalog.LIST_MODELS_NAME,
                ServerBuiltInToolCatalog.LIST_MODEL_SETTINGS_NAME,
                ServerBuiltInToolCatalog.LIST_TOOLS_NAME,
                ServerBuiltInToolCatalog.READ_TOOL_NAME,
                ServerBuiltInToolCatalog.LIST_MODEL_PRESETS_NAME,
                ServerBuiltInToolCatalog.READ_MODEL_PRESET_NAME,
                ServerBuiltInToolCatalog.CREATE_MODEL_PRESET_NAME,
                ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME,
                ServerBuiltInToolCatalog.DELETE_MODEL_PRESET_NAME,
                ServerBuiltInToolCatalog.GET_CURRENT_SESSION_INFO_NAME,
            ),
            names
        )
        // Every description states user-scoping so the LLM can self-correct on authorization errors.
        ServerBuiltInToolCatalog.allTools.forEach { spec ->
            assertTrue(spec.description.contains("current user"), "Description must state user scoping: ${spec.name}")
        }
        // Every schema carries a type/properties key so tool validation accepts it.
        ServerBuiltInToolCatalog.allTools.forEach { spec ->
            assertTrue(
                spec.inputSchema.containsKey("type") || spec.inputSchema.containsKey("properties"),
                "Schema must be a valid JSON Schema: ${spec.name}"
            )
        }
    }

    /**
     * Verifies the five model-preset specs declare their parameters correctly: the list tool is
     * parameterless (empty-object schema, no `required` list), the read/update/delete tools require
     * `model_preset_id`, the write tools expose `model_id`/`model_settings_id` under the
     * catalog-owned property names, and `update_model_preset`'s description documents the `0`
     * "clear the reference" sentinel the LLM must use for patch semantics.
     */
    @Test
    fun `model preset specs declare their parameters and the clear sentinel`() {
        fun propertiesOf(name: String) = requireNotNull(ServerBuiltInToolCatalog.specFor(name))
            .inputSchema["properties"]!!.jsonObject

        fun requiredOf(name: String) = requireNotNull(ServerBuiltInToolCatalog.specFor(name))
            .inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        // Parameterless: no parameters to hallucinate, and no required list.
        val listSpec = requireNotNull(ServerBuiltInToolCatalog.specFor(ServerBuiltInToolCatalog.LIST_MODEL_PRESETS_NAME))
        assertEquals("object", listSpec.inputSchema["type"]!!.jsonPrimitive.content)
        assertTrue(propertiesOf(ServerBuiltInToolCatalog.LIST_MODEL_PRESETS_NAME).isEmpty())
        assertEquals(null, listSpec.inputSchema["required"])

        // The preset id is the only required parameter of read/update/delete.
        listOf(
            ServerBuiltInToolCatalog.READ_MODEL_PRESET_NAME,
            ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME,
            ServerBuiltInToolCatalog.DELETE_MODEL_PRESET_NAME
        ).forEach { name ->
            assertEquals(
                "integer",
                propertiesOf(name)[ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY]!!
                    .jsonObject["type"]!!.jsonPrimitive.content
            )
            assertTrue(ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY in requiredOf(name))
        }

        // create_model_preset requires only the name and exposes both optional references.
        assertEquals(listOf(ServerBuiltInToolCatalog.NAME_PROPERTY), requiredOf(ServerBuiltInToolCatalog.CREATE_MODEL_PRESET_NAME))
        listOf(ServerBuiltInToolCatalog.CREATE_MODEL_PRESET_NAME, ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME)
            .forEach { name ->
                val properties = propertiesOf(name)
                assertEquals("integer", properties[ServerBuiltInToolCatalog.MODEL_ID_PROPERTY]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals(
                    "integer",
                    properties[ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY]!!.jsonObject["type"]!!.jsonPrimitive.content
                )
                // The two compaction parameters are boolean/integer on both write tools.
                assertEquals(
                    "boolean",
                    properties[ServerBuiltInToolCatalog.AUTOMATIC_COMPACTION_ENABLED_PROPERTY]!!.jsonObject["type"]!!
                        .jsonPrimitive.content
                )
                assertEquals(
                    "integer",
                    properties[ServerBuiltInToolCatalog.COMPACTION_THRESHOLD_TOKENS_PROPERTY]!!.jsonObject["type"]!!
                        .jsonPrimitive.content
                )
            }

        // update_model_preset accepts every writable field and documents the 0 = clear sentinel.
        val updateProperties = propertiesOf(ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME)
        assertEquals(
            setOf(
                ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_ID_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY,
                ServerBuiltInToolCatalog.AUTOMATIC_COMPACTION_ENABLED_PROPERTY,
                ServerBuiltInToolCatalog.COMPACTION_THRESHOLD_TOKENS_PROPERTY
            ),
            updateProperties.keys
        )
        assertEquals(
            "boolean",
            updateProperties[ServerBuiltInToolCatalog.AUTOMATIC_COMPACTION_ENABLED_PROPERTY]!!.jsonObject["type"]!!
                .jsonPrimitive.content
        )
        assertEquals(
            "integer",
            updateProperties[ServerBuiltInToolCatalog.COMPACTION_THRESHOLD_TOKENS_PROPERTY]!!.jsonObject["type"]!!
                .jsonPrimitive.content
        )
        val updateDescription = requireNotNull(
            ServerBuiltInToolCatalog.specFor(ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME)
        ).description
        assertTrue(updateDescription.contains("patch semantics"))
        assertTrue(updateDescription.contains("pass 0"))
        // The threshold's own clear sentinel is documented for the LLM, because 0 is never a stored
        // threshold.
        assertTrue(updateDescription.contains("compaction_threshold_tokens of 0"))
    }

    /**
     * Verifies the five instruction-library specs declare their parameters: only `list_instructions`
     * has no required property, every tool addressing a row requires the instruction id, the
     * authoring schema constrains the kind with the known-instruction `enum` and keeps only
     * `type`/`name` required, and the edit batch declares its `oldText`/`newText` items.
     */
    @Test
    fun `instruction specs declare their parameters and the edit batch shape`() {
        fun propertiesOf(name: String) = requireNotNull(ServerBuiltInToolCatalog.specFor(name))
            .inputSchema["properties"]!!.jsonObject

        fun requiredOf(name: String): List<String> =
            requireNotNull(ServerBuiltInToolCatalog.specFor(name))
                .inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        // list_instructions may be called with no arguments; its only parameter is the role filter.
        assertEquals(
            setOf(ServerBuiltInToolCatalog.ROLE_ID_PROPERTY),
            propertiesOf(ServerBuiltInToolCatalog.LIST_INSTRUCTIONS_NAME).keys
        )
        assertEquals(
            null, requireNotNull(ServerBuiltInToolCatalog.specFor(ServerBuiltInToolCatalog.LIST_INSTRUCTIONS_NAME))
                .inputSchema["required"]
        )

        // read/delete address one row by id, and so does edit_instruction next to its edit batch.
        listOf(
            ServerBuiltInToolCatalog.READ_INSTRUCTION_NAME,
            ServerBuiltInToolCatalog.DELETE_INSTRUCTION_NAME
        ).forEach { name ->
            assertEquals(
                "integer",
                propertiesOf(name)[ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY]!!
                    .jsonObject["type"]!!.jsonPrimitive.content
            )
            assertEquals(listOf(ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY), requiredOf(name))
        }
        assertEquals(
            listOf(
                ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY,
                ServerBuiltInToolCatalog.EDITS_PROPERTY
            ),
            requiredOf(ServerBuiltInToolCatalog.EDIT_INSTRUCTION_NAME)
        )

        // create_instruction requires the kind and the label only; the text and the kind-specific
        // data are optional, and the kind is constrained to the known instruction kinds.
        val createProperties = propertiesOf(ServerBuiltInToolCatalog.CREATE_INSTRUCTION_NAME)
        assertEquals(
            setOf(
                ServerBuiltInToolCatalog.TYPE_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.MESSAGE_PROPERTY,
                ServerBuiltInToolCatalog.CUSTOM_PROPERTY
            ),
            createProperties.keys
        )
        assertEquals(
            listOf(ServerBuiltInToolCatalog.TYPE_PROPERTY, ServerBuiltInToolCatalog.NAME_PROPERTY),
            requiredOf(ServerBuiltInToolCatalog.CREATE_INSTRUCTION_NAME)
        )
        assertEquals(
            AgentInstructionTypes.allKnown,
            createProperties[ServerBuiltInToolCatalog.TYPE_PROPERTY]!!.jsonObject["enum"]!!
                .jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            "object",
            createProperties[ServerBuiltInToolCatalog.CUSTOM_PROPERTY]!!.jsonObject["type"]!!
                .jsonPrimitive.content
        )

        // The edit batch mirrors the worker edit_file shape: a non-empty array of two-string items.
        val edits = propertiesOf(ServerBuiltInToolCatalog.EDIT_INSTRUCTION_NAME)[ServerBuiltInToolCatalog.EDITS_PROPERTY]!!
            .jsonObject
        assertEquals("array", edits["type"]!!.jsonPrimitive.content)
        assertEquals(1L, edits["minItems"]!!.jsonPrimitive.content.toLong())
        val editsItem = edits["items"]!!.jsonObject
        assertEquals("object", editsItem["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(
                ServerBuiltInToolCatalog.OLD_TEXT_PROPERTY,
                ServerBuiltInToolCatalog.NEW_TEXT_PROPERTY
            ),
            editsItem["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            setOf(
                ServerBuiltInToolCatalog.OLD_TEXT_PROPERTY,
                ServerBuiltInToolCatalog.NEW_TEXT_PROPERTY
            ),
            editsItem["properties"]!!.jsonObject.keys
        )
    }

    /**
     * Verifies the `delete_instruction` description warns that a linked row cannot be deleted and
     * names the way to unlink it, so the model does not promise a deletion the server refuses.
     */
    @Test
    fun `delete instruction description states the linked-row refusal`() {
        val description = requireNotNull(
            ServerBuiltInToolCatalog.specFor(ServerBuiltInToolCatalog.DELETE_INSTRUCTION_NAME)
        ).description

        assertTrue(description.contains("instruction_in_use"))
        assertTrue(description.contains(ServerBuiltInToolCatalog.UPDATE_AGENT_ROLE_NAME))
    }
}
