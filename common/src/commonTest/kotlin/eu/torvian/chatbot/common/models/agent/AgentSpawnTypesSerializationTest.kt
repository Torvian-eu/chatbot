package eu.torvian.chatbot.common.models.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Serialization round-trip tests for the `spawn_agent` / `send_message` operator-tool wire contract.
 *
 * Verifies that the shared types survive a JSON encode → decode cycle with the default shared
 * `Json` codec, including the sealed-interface discriminators used by the WebSocket protocol, the
 * shared `OperatorToolMode` wire values, and the two-tool catalog order contract (spawn first,
 * send_message second).
 */
class AgentSpawnTypesSerializationTest {

    private val json = Json

    private val sampleRole = AgentRoleDto(
        id = 7L,
        name = "implementer",
        displayName = "Implementer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 5L,
        tools = setOf(1L, 2L),
        instructions = emptyList()
    )

    /**
     * Verifies that the subject survives serialization alongside the existing spawn request fields.
     * Also pins the default-mode contract: with `encodeDefaults = false` the absent `mode` is
     * omitted from the wire and decodes back to [OperatorToolMode.WAIT_FOR_RESPONSE].
     */
    @Test
    fun `AgentSpawnRequest round-trips with discriminator and default operator type`() {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = sampleRole,
            subject = "Feature implementation",
            operatorType = OperatorType.CLIENT_APP,
            conversation = listOf(AgentSpawnMessage.User("Implement the feature")),
            toolCallId = 42L
        )

        val encoded = json.encodeToString(AgentSpawnRequest.serializer(), request)
        val decoded = json.decodeFromString(AgentSpawnRequest.serializer(), encoded)

        assertEquals(request, decoded)
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, decoded.mode)
    }

    /**
     * Verifies that an explicit fire-and-forget mode survives an encode → decode round-trip and is
     * present on the wire (so the server-side builder output reaches the operator unchanged).
     */
    @Test
    fun `AgentSpawnRequest round-trips explicit fire and forget mode`() {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = sampleRole,
            subject = "Feature implementation",
            mode = OperatorToolMode.FIRE_AND_FORGET,
            operatorType = OperatorType.CLIENT_APP,
            conversation = listOf(AgentSpawnMessage.User("Implement the feature")),
            toolCallId = 42L
        )

        val encoded = json.encodeToString(AgentSpawnRequest.serializer(), request)
        val decoded = json.decodeFromString(AgentSpawnRequest.serializer(), encoded)

        assertEquals(request, decoded)
        assertEquals(OperatorToolMode.FIRE_AND_FORGET, decoded.mode)
        assertTrue(encoded.contains("\"mode\":\"fire_and_forget\""))
    }

    /**
     * Verifies the matched-version forward-compat path: a wire payload without the `mode` key
     * decodes to [OperatorToolMode.WAIT_FOR_RESPONSE], preserving default (summary-return) mode for
     * requests built before the field existed.
     */
    @Test
    fun `AgentSpawnRequest missing mode key defaults to wait for response`() {
        val encodedWithoutKey = json.encodeToString(
            AgentSpawnRequest.serializer(),
            AgentSpawnRequest(
                agentRoleToSpawn = sampleRole,
                subject = "Feature implementation",
                conversation = listOf(AgentSpawnMessage.User("Implement the feature")),
                toolCallId = 42L
            )
        )

        // encodeDefaults = false omits the defaulted mode from the wire entirely.
        assertFalse(encodedWithoutKey.contains("\"mode\""))
        val decoded = json.decodeFromString(AgentSpawnRequest.serializer(), encodedWithoutKey)
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, decoded.mode)
    }

    @Test
    fun `AgentSpawnMessage variants round-trip with class discriminator`() {
        val user = AgentSpawnMessage.User("prompt")
        val assistant = AgentSpawnMessage.Assistant("summary")

        assertEquals(
            user,
            json.decodeFromString(AgentSpawnMessage.serializer(), json.encodeToString(AgentSpawnMessage.serializer(), user))
        )
        assertEquals(
            assistant,
            json.decodeFromString(AgentSpawnMessage.serializer(), json.encodeToString(AgentSpawnMessage.serializer(), assistant))
        )
    }

    @Test
    fun `OperatorToolDefinition round-trips with tool_type discriminator`() {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val spawnSpec = OperatorToolCatalog.allTools.first { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
        val definition = OperatorToolDefinition(
            id = 11L,
            name = OperatorToolCatalog.SPAWN_AGENT_NAME,
            description = "Spawns an agent",
            config = buildJsonObject { },
            inputSchema = spawnSpec.inputSchema,
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            userId = 9L
        )

        val encoded = json.encodeToString(OperatorToolDefinition.serializer(), definition)
        val decoded = json.decodeFromString(OperatorToolDefinition.serializer(), encoded)

        assertEquals(definition, decoded)
        assertEquals(ToolType.OPERATOR, decoded.type)
    }

    @Test
    fun `ChatClientEvent OperatorToolCallApproval and ToolExecutionResult round-trip`() {
        val approval = ChatClientEvent.OperatorToolCallApproval(
            toolCallId = 1L,
            approved = true,
            denialReason = null
        )
        val encodedApproval = json.encodeToString(ChatClientEvent.serializer(), approval)
        assertEquals(approval, json.decodeFromString(ChatClientEvent.serializer(), encodedApproval))

        val result = ChatClientEvent.ToolExecutionResult(
            toolCallId = 1L,
            output = "summary",
            isError = false,
            errorMessage = null
        )
        val encodedResult = json.encodeToString(ChatClientEvent.serializer(), result)
        assertEquals(result, json.decodeFromString(ChatClientEvent.serializer(), encodedResult))
    }

    @Test
    fun `OperatorToolExecutionRequested round-trips on both stream and non-stream surfaces`() {
        val payload = "{\"agentRoleToSpawn\":{}}"

        val streamEvent = ChatStreamEvent.OperatorToolExecutionRequested(
            toolCallId = 3L,
            toolName = OperatorToolCatalog.SEND_MESSAGE_NAME,
            payload = payload
        )
        val encodedStream = json.encodeToString(ChatStreamEvent.serializer(), streamEvent)
        assertEquals(streamEvent, json.decodeFromString(ChatStreamEvent.serializer(), encodedStream))

        val event = ChatEvent.OperatorToolExecutionRequested(
            toolCallId = 3L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = payload
        )
        val encoded = json.encodeToString(ChatEvent.serializer(), event)
        assertEquals(event, json.decodeFromString(ChatEvent.serializer(), encoded))
        assertEquals("server_tool_execution_requested", event.eventType)
    }

    /**
     * Verifies that the catalog keeps `spawn_agent` first and `send_message` second — the order is
     * a documented contract consumed by seeding and the client's operator-tool list.
     */
    @Test
    fun `OperatorToolCatalog holds spawn_agent then send_message in stable order`() {
        assertEquals(2, OperatorToolCatalog.allTools.size)
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, OperatorToolCatalog.allTools[0].name)
        assertEquals(OperatorToolCatalog.SEND_MESSAGE_NAME, OperatorToolCatalog.allTools[1].name)
    }

    /**
     * Verifies that the LLM-facing schema requires the subject, role name, and prompt — and that the
     * optional `mode` property is deliberately kept out of `required`.
     */
    @Test
    fun `OperatorToolCatalog spawn_agent schema declares all required parameters`() {
        val spec = OperatorToolCatalog.allTools.first { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, spec.name)
        val required = (spec.inputSchema["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertTrue(required.contains(OperatorToolCatalog.SPAWN_AGENT_SUBJECT_PROPERTY))
        assertTrue(required.contains(OperatorToolCatalog.SPAWN_AGENT_ROLE_NAME_PROPERTY))
        assertTrue(required.contains(OperatorToolCatalog.SPAWN_AGENT_PROMPT_PROPERTY))
        // The mode property is optional; making it required would break default-mode calls.
        assertFalse(required.contains(OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY))
    }

    /**
     * Verifies that the LLM-facing schema advertises `mode` as an optional enum property carrying
     * the shared wire values, and that the legacy `interactive` property is gone.
     */
    @Test
    fun `OperatorToolCatalog spawn_agent schema declares mode as an optional enum property`() {
        val spec = OperatorToolCatalog.allTools.first { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
        val properties = spec.inputSchema["properties"] as JsonObject
        val mode = properties[OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY] as JsonObject
        assertEquals("string", mode["type"]?.jsonPrimitive?.content)
        val enumValues = (mode["enum"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("wait_for_response", "fire_and_forget"), enumValues)
        // The replaced interactive flag must no longer be advertised.
        assertNull(properties["interactive"])
    }

    /**
     * Verifies that the `send_message` schema requires `chat_session_id` (integer) and `message`
     * (string), and exposes the same optional `mode` enum with the shared wire values.
     */
    @Test
    fun `OperatorToolCatalog send_message schema declares chat_session_id message and mode`() {
        val spec = OperatorToolCatalog.allTools.first { it.name == OperatorToolCatalog.SEND_MESSAGE_NAME }
        assertEquals(OperatorToolCatalog.SEND_MESSAGE_NAME, spec.name)
        val required = (spec.inputSchema["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertTrue(required.contains(OperatorToolCatalog.SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY))
        assertTrue(required.contains(OperatorToolCatalog.SEND_MESSAGE_MESSAGE_PROPERTY))
        assertFalse(required.contains(OperatorToolCatalog.SEND_MESSAGE_MODE_PROPERTY))

        val properties = spec.inputSchema["properties"] as JsonObject
        assertEquals(
            "integer",
            (properties[OperatorToolCatalog.SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY] as JsonObject)["type"]?.jsonPrimitive?.content
        )
        assertEquals(
            "string",
            (properties[OperatorToolCatalog.SEND_MESSAGE_MESSAGE_PROPERTY] as JsonObject)["type"]?.jsonPrimitive?.content
        )
        val mode = properties[OperatorToolCatalog.SEND_MESSAGE_MODE_PROPERTY] as JsonObject
        assertEquals("string", mode["type"]?.jsonPrimitive?.content)
        val enumValues = (mode["enum"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("wait_for_response", "fire_and_forget"), enumValues)
    }

    /**
     * Verifies that `OperatorToolMode` serializes to exactly the Q1-b wire values and decodes back
     * from them (the values are simultaneously LLM-facing schema values and relay DTO values).
     */
    @Test
    fun `OperatorToolMode serializes to the shared wire values`() {
        assertEquals(
            "\"wait_for_response\"",
            json.encodeToString(OperatorToolMode.serializer(), OperatorToolMode.WAIT_FOR_RESPONSE)
        )
        assertEquals(
            "\"fire_and_forget\"",
            json.encodeToString(OperatorToolMode.serializer(), OperatorToolMode.FIRE_AND_FORGET)
        )
        assertEquals(
            OperatorToolMode.WAIT_FOR_RESPONSE,
            json.decodeFromString(OperatorToolMode.serializer(), "\"wait_for_response\"")
        )
        assertEquals(
            OperatorToolMode.FIRE_AND_FORGET,
            json.decodeFromString(OperatorToolMode.serializer(), "\"fire_and_forget\"")
        )
    }

    /**
     * Verifies that [SendMessageRequest] round-trips all fields including an explicit mode.
     */
    @Test
    fun `SendMessageRequest round-trips all fields`() {
        val request = SendMessageRequest(
            chatSessionId = 9L,
            message = "Continue please",
            mode = OperatorToolMode.FIRE_AND_FORGET,
            toolCallId = 42L
        )

        val encoded = json.encodeToString(SendMessageRequest.serializer(), request)
        val decoded = json.decodeFromString(SendMessageRequest.serializer(), encoded)

        assertEquals(request, decoded)
        assertTrue(encoded.contains("\"fire_and_forget\""))
    }

    /**
     * Verifies that the default mode is omitted from the wire and decodes back to wait mode.
     */
    @Test
    fun `SendMessageRequest defaults to wait mode on the wire`() {
        val request = SendMessageRequest(
            chatSessionId = 9L,
            message = "Continue please",
            toolCallId = 42L
        )

        val encoded = json.encodeToString(SendMessageRequest.serializer(), request)
        assertFalse(encoded.contains("\"mode\""))
        val decoded = json.decodeFromString(SendMessageRequest.serializer(), encoded)
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, decoded.mode)
    }
}