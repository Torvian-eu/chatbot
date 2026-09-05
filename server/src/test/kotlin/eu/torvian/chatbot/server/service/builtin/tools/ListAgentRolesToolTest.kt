package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ListAgentRolesTool].
 *
 * Covers the complete role projection, type-only instruction summaries and null handling, the
 * catalog guidance, the empty-list case, and strict rejection of input parameters.
 */
class ListAgentRolesToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    /**
     * Creates a role fixture with configurable nullable and collection properties.
     *
     * @param id Role identifier.
     * @param name Machine-readable role name.
     * @param displayName Optional human-readable role name.
     * @param description Role description.
     * @param modelId Optional model identifier.
     * @param modelSettingsId Optional model-settings identifier.
     * @param tools Attached tool identifiers.
     * @param spawnableAgentRoleIds Roles this role may spawn.
     * @param instructions Ordered instructions belonging to the role.
     * @return A role DTO suitable for list-tool assertions.
     */
    private fun sampleRole(
        id: Long = 1L,
        name: String = "writer",
        displayName: String? = "Writer",
        description: String = "Writes code",
        modelId: Long? = 3L,
        modelSettingsId: Long? = 4L,
        tools: Set<Long> = setOf(5L, 6L),
        spawnableAgentRoleIds: Set<Long> = setOf(2L),
        instructions: List<AgentInstructionDto> = listOf(
            AgentInstructionDto(
                type = AgentInstructionTypes.ROLE,
                name = "Role instruction name",
                message = "SENSITIVE role instruction message"
            ),
            AgentInstructionDto(
                type = AgentInstructionTypes.CUSTOM,
                name = "Custom instruction name",
                message = "SENSITIVE custom instruction message",
                custom = buildJsonObject {
                    put("secretMetadata", "SENSITIVE custom metadata")
                }
            )
        )
    ) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        tools = tools,
        spawnableAgentRoleIds = spawnableAgentRoleIds,
        instructions = instructions
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    /**
     * Verifies that each role contains every DTO property while instruction content stays private.
     */
    @Test
    fun `returns all role properties with type-only instruction summaries`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getAllRolesForUser(userId) } returns listOf(
            sampleRole(),
            sampleRole(
                id = 2L,
                name = "editor",
                displayName = null,
                description = "Edits",
                modelId = null,
                modelSettingsId = null,
                tools = emptySet(),
                spawnableAgentRoleIds = emptySet(),
                instructions = emptyList()
            )
        )
        val tool = ListAgentRolesTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))
        val roles = json.parseToJsonElement(output).jsonArray
        val expectedKeys = setOf(
            "id",
            "name",
            "displayName",
            "description",
            "modelId",
            "modelSettingsId",
            "tools",
            "spawnableAgentRoleIds",
            "instructions",
            "disabled"
        )

        assertEquals(2, roles.size)

        val writer = roles[0].jsonObject
        assertEquals(expectedKeys, writer.keys)
        assertEquals(1L, writer.getValue("id").jsonPrimitive.long)
        assertEquals("writer", writer.getValue("name").jsonPrimitive.content)
        assertEquals("Writer", writer.getValue("displayName").jsonPrimitive.content)
        assertEquals("Writes code", writer.getValue("description").jsonPrimitive.content)
        assertEquals(3L, writer.getValue("modelId").jsonPrimitive.long)
        assertEquals(4L, writer.getValue("modelSettingsId").jsonPrimitive.long)
        // The per-user flag is always present (boolean, never omitted).
        assertEquals(false, writer.getValue("disabled").jsonPrimitive.boolean)
        assertEquals(
            setOf(5L, 6L),
            writer.getValue("tools").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )
        assertEquals(
            setOf(2L),
            writer.getValue("spawnableAgentRoleIds").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )
        assertEquals(
            listOf(AgentInstructionTypes.ROLE, AgentInstructionTypes.CUSTOM),
            writer.getValue("instructions").jsonArray.map { it.jsonPrimitive.content }
        )

        val editor = roles[1].jsonObject
        assertEquals(expectedKeys, editor.keys)
        assertEquals(JsonNull, editor.getValue("displayName"))
        assertEquals(JsonNull, editor.getValue("modelId"))
        assertEquals(JsonNull, editor.getValue("modelSettingsId"))
        assertEquals(emptyList(), editor.getValue("tools").jsonArray)
        assertEquals(emptyList(), editor.getValue("spawnableAgentRoleIds").jsonArray)
        assertEquals(emptyList(), editor.getValue("instructions").jsonArray)
        // The disabled flag is always present even for roles without a side-table row.
        assertEquals(false, editor.getValue("disabled").jsonPrimitive.boolean)

        assertFalse(output.contains("Role instruction name"))
        assertFalse(output.contains("Custom instruction name"))
        assertFalse(output.contains("SENSITIVE role instruction message"))
        assertFalse(output.contains("SENSITIVE custom instruction message"))
        assertFalse(output.contains("SENSITIVE custom metadata"))
    }

    /**
     * Verifies that the tool description documents the expanded result and detailed-read path.
     */
    @Test
    fun `describes expanded output and full instruction lookup`() {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = ListAgentRolesTool(agentRoleService, json)

        assertTrue(tool.description.contains("model id"))
        assertTrue(tool.description.contains("model settings id"))
        assertTrue(tool.description.contains("attached tool ids"))
        assertTrue(tool.description.contains("spawnable role ids"))
        assertTrue(tool.description.contains("instruction types only"))
        assertTrue(tool.description.contains("read_agent_role"))
        assertTrue(tool.description.contains("full instruction contents"))
    }

    /**
     * Verifies that the per-user disabled flag is projected for disabled roles too.
     */
    @Test
    fun `projects the disabled flag for roles disabled by the current user`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getAllRolesForUser(userId) } returns listOf(
            sampleRole(id = 1L).copy(disabled = true),
            sampleRole(id = 2L, name = "editor").copy(disabled = false)
        )
        val tool = ListAgentRolesTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))
        val roles = json.parseToJsonElement(output).jsonArray

        assertEquals(true, roles[0].jsonObject.getValue("disabled").jsonPrimitive.boolean)
        assertEquals(false, roles[1].jsonObject.getValue("disabled").jsonPrimitive.boolean)
    }

    /**
     * Verifies that no roles are still represented by the exact empty JSON array.
     */
    @Test
    fun `returns an empty array when the user has no roles`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getAllRolesForUser(userId) } returns emptyList()
        val tool = ListAgentRolesTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))

        assertEquals("[]", output)
    }

    /**
     * Verifies that unknown parameters fail validation before the user-scoped service is called.
     */
    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = ListAgentRolesTool(agentRoleService, json)

        val result = tool.execute(userId, buildJsonObject { put("foo", "bar") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'foo'"))
        coVerify(exactly = 0) { agentRoleService.getAllRolesForUser(any()) }
    }
}
