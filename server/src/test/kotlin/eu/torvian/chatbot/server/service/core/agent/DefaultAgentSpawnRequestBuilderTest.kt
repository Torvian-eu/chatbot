package eu.torvian.chatbot.server.service.core.agent

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.OperatorType
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultAgentSpawnRequestBuilder].
 *
 * Covers the happy path (owner-scoped role-id + allow-list resolution for unassociated, same-project and
 * cross-project targets), the three typed errors (`InvalidInput` for malformed or legacy input,
 * `RoleNotFound` for unknown/foreign ids, `RoleNotAllowed` for ungranted ids or an unloadable source
 * role), and the mode/argument parsing rules.
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
     * Builds the execution context consumed by [DefaultAgentSpawnRequestBuilder.build].
     *
     * @param userId Caller identity; forwarded to the role service.
     * @param agentRoleId Source role id from the validated session; forwarded to the role service.
     * @param projectId Session project scope; `null` (default) means the session has no project. It has
     *            no effect on target resolution, which follows the requested role id.
     * @return A fully-populated context for builder tests.
     */
    private fun context(
        userId: Long = 1L,
        agentRoleId: Long = 1L,
        projectId: Long? = null
    ): ToolCallExecutionContext = ToolCallExecutionContext(
        userId = userId,
        sessionId = 10L,
        sessionName = "Session",
        agentRoleId = agentRoleId,
        projectId = projectId
    )

    /**
     * Creates a persisted spawn-tool call with valid subject, role id, and prompt arguments by default.
     *
     * @param id Identifier copied to the eventual spawn request.
     * @param input Raw JSON arguments to place on the tool call.
     * @return A tool call suitable for request-builder tests.
     */
    private fun toolCall(id: Long = 42L, input: String? = """{"subject":"Implementation task","agent_role_id":5,"prompt":"Do the thing"}"""): ToolCall =
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
     * Verifies that valid arguments resolve the role by id and preserve the supplied subject.
     */
    @Test
    fun `build resolves the role by id and assembles the request`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(), toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        val request = result.getOrNull()!!
        assertEquals(role, request.agentRoleToSpawn)
        assertEquals("Implementation task", request.subject)
        // Absent mode → default wait-for-response (summary-return) behavior.
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, request.mode)
        assertEquals(OperatorType.CLIENT_APP, request.operatorType)
        assertEquals(42L, request.toolCallId)
        assertEquals(listOf(AgentSpawnMessage.User("Do the thing")), request.conversation)
        // An unassociated role spawned from a project-less session stays project-less (null).
        assertNull(request.projectId)
    }

    /**
     * Verifies that the role id decides the target: the target's project scopes the spawned session, so a
     * granted role from another project is spawnable even though the calling session lives elsewhere.
     */
    @Test
    fun `build accepts a granted target from another project and carries the target's project`() = runTest {
        val projectRole = role.copy(projectId = 7L)
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns projectRole.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(projectId = 3L), toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(projectRole, result.getOrNull()!!.agentRoleToSpawn)
        assertEquals(7L, result.getOrNull()!!.projectId)
    }

    /**
     * Verifies that a granted target which happens to share its name with another of the user's roles
     * spawns without error: only the requested id is read, so the duplicate name is inert.
     */
    @Test
    fun `build spawns the granted target even when another role shares its name`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(), toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(role, result.getOrNull()!!.agentRoleToSpawn)
        // The same-named sibling (id 9) is never resolved: names play no part in resolution.
        coVerify(exactly = 1) { agentRoleService.getRoleById(1L, 5L) }
        coVerify(exactly = 0) { agentRoleService.getRoleById(1L, 9L) }
    }

    /**
     * Verifies that an owned id absent from the source role's allow-list is rejected as not allowed.
     */
    @Test
    fun `build rejects an owned id outside the allow-list`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns
            sourceRole.copy(spawnableAgentRoleIds = emptySet()).right()

        val result = builder.build(context(), toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotAllowed>(result.leftOrNull())
        assertEquals(5L, error.roleId)
    }

    /**
     * Verifies that an id that cannot be loaded is reported as not found with the requested id, before the
     * source role is even read. A foreign role collapses into the same error (the lookup is owner-scoped),
     * so both cases are indistinguishable to the caller.
     */
    @Test
    fun `build maps an unknown or foreign role id to RoleNotFound`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 404L) } returns AgentRoleError.NotFound(404L).left()

        val result = builder.build(
            context(),
            toolCall(input = """{"subject":"Ghost task","agent_role_id":404,"prompt":"hi"}""")
        )

        val error = assertIs<SpawnRequestBuildError.RoleNotFound>(result.leftOrNull())
        assertEquals(404L, error.roleId)
        coVerify(exactly = 0) { agentRoleService.getRoleById(1L, 1L) }
    }

    /**
     * Verifies that an unloadable source role is rejected with the requested target id, so the builder
     * never reveals whether the source role exists.
     */
    @Test
    fun `build reports RoleNotAllowed when the source role no longer exists`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns AgentRoleError.NotFound(1L).left()

        val result = builder.build(context(), toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotAllowed>(result.leftOrNull())
        assertEquals(5L, error.roleId)
    }

    /**
     * Verifies that an explicit `mode: fire_and_forget` is validated and carried through into the
     * request unchanged, with all remaining fields untouched.
     */
    @Test
    fun `build carries fire and forget mode into the request`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(
                input = """{"subject":"Implementation task","agent_role_id":5,"prompt":"Do the thing","mode":"fire_and_forget"}"""
            )
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        val request = result.getOrNull()!!
        assertEquals(OperatorToolMode.FIRE_AND_FORGET, request.mode)
        assertEquals(role, request.agentRoleToSpawn)
        assertEquals("Implementation task", request.subject)
        assertEquals(OperatorType.CLIENT_APP, request.operatorType)
        assertEquals(42L, request.toolCallId)
        assertEquals(listOf(AgentSpawnMessage.User("Do the thing")), request.conversation)
        // An unassociated role spawned from a project-less session stays project-less (null).
        assertNull(request.projectId)
    }

    /**
     * Verifies that a present-but-invalid `mode` value is rejected as
     * [SpawnRequestBuildError.InvalidInput] before any role read: argument validation completes before
     * I/O, so the builder never leaks whether a role exists for malformed input.
     */
    @Test
    fun `build rejects an invalid mode value before role lookup`() = runTest {
        val malformedModes = listOf(
            // Explicit JSON null is also malformed: the builder must not silently fall back.
            """{"subject":"Task","agent_role_id":5,"prompt":"Do the thing","mode":null}""",
            """{"subject":"Task","agent_role_id":5,"prompt":"Do the thing","mode":"yes"}""",
            """{"subject":"Task","agent_role_id":5,"prompt":"Do the thing","mode":1}""",
            """{"subject":"Task","agent_role_id":5,"prompt":"Do the thing","mode":[true]}""",
            """{"subject":"Task","agent_role_id":5,"prompt":"Do the thing","mode":{"x":1}}"""
        )

        malformedModes.forEach { input ->
            val result = builder.build(context(), toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        // Validation precedes I/O: no role read may have happened for malformed input.
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that a legacy `interactive` key (from a stale LLM schema) is ignored by the builder's
     * property-name lookup and therefore falls back to the default wait-for-response mode — the wire
     * change is not a compatibility break for stale model output.
     */
    @Test
    fun `build ignores a legacy interactive key and defaults to wait mode`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(
                input = """{"subject":"Implementation task","agent_role_id":5,"prompt":"Do the thing","interactive":true}"""
            )
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, result.getOrNull()!!.mode)
    }

    /**
     * Verifies the legacy-input path: a persisted or pending call that still carries `agent_role_name`
     * and no id fails with a clear [SpawnRequestBuildError.InvalidInput] pointing at the available-agents
     * table — there is no name fallback.
     */
    @Test
    fun `build rejects a name-only legacy payload with InvalidInput`() = runTest {
        val result = builder.build(
            context(),
            toolCall(input = """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing"}""")
        )

        val error = assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        assertTrue(error.reason.contains(OperatorToolCatalog.SPAWN_AGENT_ROLE_ID_PROPERTY), error.reason)
        assertTrue(error.reason.contains("available agents table"), error.reason)
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that a payload carrying both the id and a stale `agent_role_name` key succeeds: unknown
     * keys are ignored, so a cooperating model is not punished for the schema drift.
     */
    @Test
    fun `build ignores a stale agent_role_name key when the id is present`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(
                input = """{"subject":"Task","agent_role_id":5,"agent_role_name":"stale","prompt":"Do the thing"}"""
            )
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(5L, result.getOrNull()!!.agentRoleToSpawn.id)
    }

    /**
     * Verifies that a numeric JSON string is accepted as the role id (the same convention the server
     * built-in tools use via `parseRequiredLong`).
     */
    @Test
    fun `build accepts a numeric string role id`() = runTest {
        coEvery { agentRoleService.getRoleById(1L, 5L) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(input = """{"subject":"Task","agent_role_id":"5","prompt":"Do the thing"}""")
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(5L, result.getOrNull()!!.agentRoleToSpawn.id)
    }

    /**
     * Verifies that every non-integer shape of the id is rejected before any role read, including an
     * absent key (the legacy name-only payload) and explicit `null`.
     */
    @Test
    fun `build rejects a missing or non-integer role id`() = runTest {
        val invalidInputs = listOf(
            """{"subject":"Task","prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":"","prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":"abc","prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":1.5,"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":true,"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":null,"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":[5],"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":{"id":5},"prompt":"Do the thing"}"""
        )

        invalidInputs.forEach { input ->
            val result = builder.build(context(), toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that subject and prompt omissions are rejected before role lookup.
     */
    @Test
    fun `build rejects missing or blank parameters`() = runTest {
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"agent_role_id":5,"prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"subject":"","agent_role_id":5,"prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"subject":"Task","agent_role_id":5}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = null)).leftOrNull()
        )
    }

    @Test
    fun `build rejects malformed input JSON`() = runTest {
        val result = builder.build(context(), toolCall(input = "not json"))

        assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
    }

    /**
     * Verifies that structured values are reported as invalid tool input instead of escaping as
     * [IllegalArgumentException] from kotlinx.serialization and terminating the chat WebSocket.
     */
    @Test
    fun `build rejects non primitive required parameters`() = runTest {
        val invalidInputs = listOf(
            """{"subject":["Task"],"agent_role_id":5,"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_id":5,"prompt":{"text":"Do the thing"}}"""
        )

        invalidInputs.forEach { input ->
            val result = builder.build(context(), toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
    }
}
