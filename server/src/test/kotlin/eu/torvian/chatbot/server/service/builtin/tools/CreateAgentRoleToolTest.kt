package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CreateAgentRoleTool].
 *
 * Covers input validation (required `name`, optional fields, unknown parameters, accumulated
 * errors), the mapping of the parsed input into a [CreateAgentRoleRequest] (including the
 * model-less create path), and the mapping of [CreateAgentRoleError]s to LLM-readable handler
 * errors.
 */
class CreateAgentRoleToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private fun createdRole(id: Long = 9L, name: String = "translator") = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "translates",
        modelId = null,
        modelSettingsId = null,
        tools = emptySet(),
        spawnableAgentRoleIds = emptySet(),
        instructions = emptyList()
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the name property`() = runTest {
        val tool = CreateAgentRoleTool(mockk(), json)

        val result = tool.execute(userId, buildJsonObject { put("description", "no name") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: name"))
    }

    @Test
    fun `creates a model-less role and maps the request`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.createRole(userId, any()) } returns createdRole().right()
        val tool = CreateAgentRoleTool(agentRoleService, json)

        val output = assertSuccess(
            tool.execute(userId, buildJsonObject { put("name", "translator"); put("description", "translates") })
        )
        assertTrue(output.contains("\"id\":9"))

        coVerify(exactly = 1) {
            agentRoleService.createRole(
                userId,
                match<CreateAgentRoleRequest> { request ->
                    request.name == "translator" &&
                            request.description == "translates" &&
                            request.modelId == null &&
                            request.modelSettingsId == null &&
                            request.toolIds.isEmpty() &&
                            request.spawnableAgentRoleIds.isEmpty() &&
                            request.instructions.isEmpty()
                }
            )
        }
    }

    @Test
    fun `parses every optional field including instructions`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.createRole(userId, any()) } returns createdRole().right()
        val tool = CreateAgentRoleTool(agentRoleService, json)

        val input = buildJsonObject {
            put("name", "writer")
            put("display_name", "Writer")
            put("description", "Writes code")
            put("model_id", 3L)
            put("model_settings_id", 4L)
            putJsonArray("tool_ids") { add(JsonPrimitive(5L)); add(JsonPrimitive(6L)) }
            putJsonArray("spawnable_agent_role_ids") { add(JsonPrimitive(2L)) }
            putJsonArray("instructions") {
                add(
                    buildJsonObject {
                        put("type", AgentInstructionTypes.ROLE)
                        put("name", "Role")
                        put("message", "You are a writer.")
                        put("custom", JsonNull)
                    }
                )
            }
        }
        tool.execute(userId, input)

        coVerify(exactly = 1) {
            agentRoleService.createRole(
                userId,
                match<CreateAgentRoleRequest> { request ->
                    request.name == "writer" &&
                            request.displayName == "Writer" &&
                            request.description == "Writes code" &&
                            request.modelId == 3L &&
                            request.modelSettingsId == 4L &&
                            request.toolIds == setOf(5L, 6L) &&
                            request.spawnableAgentRoleIds == setOf(2L) &&
                            request.instructions.size == 1 &&
                            request.instructions.first().type == AgentInstructionTypes.ROLE
                }
            )
        }
    }

    @Test
    fun `maps a model-not-found failure to a readable error`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.createRole(userId, any()) } returns CreateAgentRoleError.ModelNotFound(3L).left()
        val tool = CreateAgentRoleTool(agentRoleService, json)

        val result = tool.execute(
            userId,
            buildJsonObject { put("name", "x"); put("model_id", 3L); put("model_settings_id", 4L) }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("model_not_found", error.code)
        assertTrue(error.message.contains("Model 3"))
    }

    @Test
    fun `maps a name-already-exists failure to a readable error`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.createRole(userId, any()) } returns
                CreateAgentRoleError.NameAlreadyExists("writer").left()
        val tool = CreateAgentRoleTool(agentRoleService, json)

        val result = tool.execute(userId, buildJsonObject { put("name", "writer") })

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("name_already_exists", error.code)
    }

    @Test
    fun `accumulates every validation error before failing`() = runTest {
        val tool = CreateAgentRoleTool(mockk(), json)

        val result = tool.execute(
            userId,
            buildJsonObject { put("name", 123); put("model_id", "not-a-number"); put("unknown", true) }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        // All three issues are reported at once so the LLM can fix them in a single turn.
        assertTrue(error.message.contains("3 error(s)"), "Expected 3 accumulated errors in: ${error.message}")
        assertTrue(error.message.contains("Argument 'name' must be a string"))
        assertTrue(error.message.contains("Argument 'model_id' must be an integer"))
        assertTrue(error.message.contains("Unknown parameter: 'unknown'"))
    }

    @Test
    fun `rejects a malformed instructions array`() = runTest {
        val tool = CreateAgentRoleTool(mockk(), json)

        val result = tool.execute(userId, buildJsonObject { put("name", "x"); put("instructions", "nope") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'instructions' must be an array of instruction objects"))
    }

    @Test
    fun `rejects a non-integer tool id inside the tool_ids array`() = runTest {
        val tool = CreateAgentRoleTool(mockk(), json)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("name", "x")
                putJsonArray("tool_ids") { add(JsonPrimitive("not-an-id")) }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'tool_ids[0]' must be an integer"))
    }
}
