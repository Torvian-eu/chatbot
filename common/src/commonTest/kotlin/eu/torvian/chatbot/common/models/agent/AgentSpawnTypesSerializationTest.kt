package eu.torvian.chatbot.common.models.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Serialization round-trip tests for the `spawn_agent` operator-tool wire contract.
 *
 * Verifies that the new shared types survive a JSON encode → decode cycle with the default shared
 * `Json` codec, including the sealed-interface discriminators used by the WebSocket protocol.
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
        val definition = OperatorToolDefinition(
            id = 11L,
            name = OperatorToolCatalog.SPAWN_AGENT_NAME,
            description = "Spawns an agent",
            config = buildJsonObject { },
            inputSchema = OperatorToolCatalog.allTools.first().inputSchema,
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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
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
     * Verifies that the LLM-facing schema requires the subject, role name, and prompt.
     */
    @Test
    fun `OperatorToolCatalog spawn_agent schema declares all required parameters`() {
        val spec = OperatorToolCatalog.allTools.single()
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, spec.name)
        val required = spec.inputSchema["required"]?.toString()
        assertTrue(required != null && required.contains(OperatorToolCatalog.SPAWN_AGENT_SUBJECT_PROPERTY))
        assertTrue(required.contains(OperatorToolCatalog.SPAWN_AGENT_ROLE_NAME_PROPERTY))
        assertTrue(required.contains(OperatorToolCatalog.SPAWN_AGENT_PROMPT_PROPERTY))
    }
}
