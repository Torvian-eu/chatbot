package eu.torvian.chatbot.server.service.core.agent

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.OperatorType
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultAgentSpawnRequestBuilder].
 *
 * Covers the happy path (role-by-name + ownership), typed errors for unknown roles, and malformed or
 * missing `spawn_agent` arguments.
 */
class DefaultAgentSpawnRequestBuilderTest {

    private val agentRoleService = mockk<AgentRoleService>()
    private val json = Json

    private val builder = DefaultAgentSpawnRequestBuilder(agentRoleService, json)

    private val role = AgentRoleDto(
        id = 5L,
        name = "implementer",
        modelId = 1L,
        modelSettingsId = 2L
    )

    /**
     * Source role whose allow-list the builder enforces; grants the [role] target by default.
     */
    private val sourceRole = AgentRoleDto(
        id = 1L,
        name = "architect",
        modelId = 1L,
        modelSettingsId = 2L,
        spawnableAgentRoleIds = setOf(role.id)
    )

    /**
     * Creates a persisted spawn-tool call with valid subject, role, and prompt arguments by default.
     *
     * @param id Identifier copied to the eventual spawn request.
     * @param input Raw JSON arguments to place on the tool call.
     * @return A tool call suitable for request-builder tests.
     */
    private fun toolCall(id: Long = 42L, input: String? = """{"subject":"Implementation task","agent_role_name":"implementer","prompt":"Do the thing"}"""): ToolCall =
        ToolCall(
            id = id,
            messageId = 100L,
            toolDefinitionId = 9L,
            toolName = "spawn_agent",
            input = input,
            status = ToolCallStatus.PENDING,
            executedAt = Instant.fromEpochMilliseconds(1L)
        )

    /**
     * Verifies that valid arguments resolve the role and preserve the supplied subject.
     */
    @Test
    fun `build resolves the role by name and assembles the request`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer") } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(1L, 1L, toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        val request = result.getOrNull()!!
        assertEquals(role, request.agentRoleToSpawn)
        assertEquals("Implementation task", request.subject)
        // Absent flag → default summary-return mode.
        assertEquals(false, request.interactive)
        assertEquals(OperatorType.CLIENT_APP, request.operatorType)
        assertEquals(42L, request.toolCallId)
        assertEquals(listOf(AgentSpawnMessage.User("Do the thing")), request.conversation)
    }

    /**
     * Verifies that an explicit `interactive: true` is validated and carried through into the
     * request unchanged, with all remaining fields untouched.
     */
    @Test
    fun `build carries interactive true into the request`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer") } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            1L,
            1L,
            toolCall(
                input = """{"subject":"Implementation task","agent_role_name":"implementer","prompt":"Do the thing","interactive":true}"""
            )
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        val request = result.getOrNull()!!
        assertEquals(true, request.interactive)
        assertEquals(role, request.agentRoleToSpawn)
        assertEquals("Implementation task", request.subject)
        assertEquals(OperatorType.CLIENT_APP, request.operatorType)
        assertEquals(42L, request.toolCallId)
        assertEquals(listOf(AgentSpawnMessage.User("Do the thing")), request.conversation)
    }

    /**
     * Verifies that a present-but-non-boolean `interactive` value is rejected as
     * [SpawnRequestBuildError.InvalidInput] before any role lookup: argument validation completes
     * before I/O, so the builder never leaks whether a role exists for malformed input.
     */
    @Test
    fun `build rejects a non boolean interactive value before role lookup`() = runTest {
        val malformedInteractive = listOf(
            // Explicit JSON null is also malformed: the builder must not silently fall back.
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","interactive":null}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","interactive":"yes"}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","interactive":1}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","interactive":[true]}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","interactive":{"x":1}}"""
        )

        malformedInteractive.forEach { input ->
            val result = builder.build(1L, 1L, toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        // Validation precedes I/O: neither role lookup may have been reached for malformed input.
        coVerify(exactly = 0) { agentRoleService.getRoleByName(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that a target outside the source role's allow-list is rejected as a logical error;
     * the source role id always comes from the validated session, never from model input.
     */
    @Test
    fun `build denies a target outside the source role allow-list`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer") } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns
            sourceRole.copy(spawnableAgentRoleIds = emptySet()).right()

        val result = builder.build(1L, 1L, toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotAllowed>(result.leftOrNull())
        assertEquals("implementer", error.roleName)
    }

    /**
     * Verifies that role lookup failures remain logical, user-facing build errors.
     */
    @Test
    fun `build maps a missing role to RoleNotFound`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "ghost") } returns AgentRoleError.NotFoundByName("ghost").left()

        val result = builder.build(1L, 1L, toolCall(input = """{"subject":"Ghost task","agent_role_name":"ghost","prompt":"hi"}"""))

        val error = assertIs<SpawnRequestBuildError.RoleNotFound>(result.leftOrNull())
        assertEquals("ghost", error.roleName)
    }

    /**
     * Verifies that subject, role-name, and prompt omissions are rejected before role lookup.
     */
    @Test
    fun `build rejects missing or blank parameters`() = runTest {
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(1L, 1L, toolCall(input = """{"agent_role_name":"x","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(1L, 1L, toolCall(input = """{"subject":"","agent_role_name":"x","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(1L, 1L, toolCall(input = """{"subject":"Task","agent_role_name":"","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(1L, 1L, toolCall(input = """{"subject":"Task","agent_role_name":"x"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(1L, 1L, toolCall(input = null)).leftOrNull()
        )
    }

    @Test
    fun `build rejects malformed input JSON`() = runTest {
        val result = builder.build(1L, 1L, toolCall(input = "not json"))

        assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
    }

    /**
     * Verifies that structured values are reported as invalid tool input instead of escaping as
     * [IllegalArgumentException] from kotlinx.serialization and terminating the chat WebSocket.
     */
    @Test
    fun `build rejects non primitive required parameters`() = runTest {
        val invalidInputs = listOf(
            """{"subject":["Task"],"agent_role_name":"implementer","prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_name":{"name":"implementer"},"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":{"text":"Do the thing"}}"""
        )

        invalidInputs.forEach { input ->
            val result = builder.build(1L, 1L, toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
    }
}
