package eu.torvian.chatbot.server.service.core.agent

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.OperatorType
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
 * Covers the happy path (role-by-name + ownership, both unassociated and project-scoped), typed
 * errors for unknown roles, and malformed or missing `spawn_agent` arguments.
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
     * @param projectId Session project scope; `null` (default) selects the unassociated scope.
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
        coEvery { agentRoleService.getRoleByName(1L, "implementer", null) } returns role.right()
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
     * Verifies that the built request carries the calling session's project when the target role
     * belongs to it: a sub-agent spawned from a project-scoped conversation stays in the same
     * project, which the operator applies before attaching the role (Session Legality Invariant).
     */
    @Test
    fun `build inherits the session project when the target role belongs to it`() = runTest {
        val projectRole = role.copy(projectId = 7L)
        coEvery { agentRoleService.getRoleByName(1L, "implementer", 7L) } returns projectRole.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(projectId = 7L), toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(projectRole, result.getOrNull()!!.agentRoleToSpawn)
        assertEquals(7L, result.getOrNull()!!.projectId)
    }

    /**
     * Verifies that a project-scoped target is rejected for a project-less session: spawns are
     * strictly same-scope, so the builder reports [SpawnRequestBuildError.RoleNotInProject] instead
     * of falling back to a different project of the role.
     */
    @Test
    fun `build rejects a project-scoped role for a project-less session`() = runTest {
        val projectRole = role.copy(projectId = 9L)
        coEvery { agentRoleService.getRoleByName(1L, "implementer", null) } returns projectRole.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(), toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotInProject>(result.leftOrNull())
        assertEquals("implementer", error.roleName)
        assertNull(error.projectId)
    }

    /**
     * Verifies that a target role outside the calling session's project is rejected: the builder
     * never spawns a role into a project it does not belong to (defense in depth — the
     * project-scoped name lookup normally never returns such a role).
     */
    @Test
    fun `build rejects a role outside the session project`() = runTest {
        val foreignRole = role.copy(projectId = 9L)
        coEvery { agentRoleService.getRoleByName(1L, "implementer", 7L) } returns foreignRole.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(projectId = 7L), toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotInProject>(result.leftOrNull())
        assertEquals("implementer", error.roleName)
        assertEquals(7L, error.projectId)
    }

    /**
     * Verifies that the name lookup is scoped to the session's project and the found role stays
     * within it: a project-attached session must resolve the role within that project (never the
     * unassociated scope) and the request carries that same project for the spawned session.
     */
    @Test
    fun `build resolves the role within the session project scope`() = runTest {
        val projectRole = role.copy(projectId = 7L)
        coEvery { agentRoleService.getRoleByName(1L, "implementer", 7L) } returns projectRole.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(context(projectId = 7L), toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(projectRole, result.getOrNull()!!.agentRoleToSpawn)
        assertEquals(7L, result.getOrNull()!!.projectId)
        // The project scope from the context must reach the user-scoped lookup.
        coVerify(exactly = 1) { agentRoleService.getRoleByName(1L, "implementer", 7L) }
    }

    /**
     * Verifies that an explicit `mode: fire_and_forget` is validated and carried through into the
     * request unchanged, with all remaining fields untouched.
     */
    @Test
    fun `build carries fire and forget mode into the request`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer", null) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(
                input = """{"subject":"Implementation task","agent_role_name":"implementer","prompt":"Do the thing","mode":"fire_and_forget"}"""
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
     * [SpawnRequestBuildError.InvalidInput] before any role lookup: argument validation completes
     * before I/O, so the builder never leaks whether a role exists for malformed input.
     */
    @Test
    fun `build rejects an invalid mode value before role lookup`() = runTest {
        val malformedModes = listOf(
            // Explicit JSON null is also malformed: the builder must not silently fall back.
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","mode":null}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","mode":"yes"}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","mode":1}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","mode":[true]}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":"Do the thing","mode":{"x":1}}"""
        )

        malformedModes.forEach { input ->
            val result = builder.build(context(), toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        // Validation precedes I/O: neither role lookup may have been reached for malformed input.
        coVerify(exactly = 0) { agentRoleService.getRoleByName(any(), any(), any()) }
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that a legacy `interactive` key (from a stale LLM schema) is ignored by the builder's
     * property-name lookup and therefore falls back to the default wait-for-response mode — the
     * wire change is not a compatibility break for stale model output.
     */
    @Test
    fun `build ignores a legacy interactive key and defaults to wait mode`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer", null) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns sourceRole.right()

        val result = builder.build(
            context(),
            toolCall(
                input = """{"subject":"Implementation task","agent_role_name":"implementer","prompt":"Do the thing","interactive":true}"""
            )
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, result.getOrNull()!!.mode)
    }

    /**
     * Verifies that a target outside the source role's allow-list is rejected as a logical error;
     * the source role id always comes from the validated session, never from model input.
     */
    @Test
    fun `build denies a target outside the source role allow-list`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "implementer", null) } returns role.right()
        coEvery { agentRoleService.getRoleById(1L, 1L) } returns
            sourceRole.copy(spawnableAgentRoleIds = emptySet()).right()

        val result = builder.build(context(), toolCall())

        val error = assertIs<SpawnRequestBuildError.RoleNotAllowed>(result.leftOrNull())
        assertEquals("implementer", error.roleName)
    }

    /**
     * Verifies that role lookup failures remain logical, user-facing build errors.
     */
    @Test
    fun `build maps a missing role to RoleNotFound`() = runTest {
        coEvery { agentRoleService.getRoleByName(1L, "ghost", null) } returns AgentRoleError.NotFoundByName("ghost").left()

        val result = builder.build(context(), toolCall(input = """{"subject":"Ghost task","agent_role_name":"ghost","prompt":"hi"}"""))

        val error = assertIs<SpawnRequestBuildError.RoleNotFound>(result.leftOrNull())
        assertEquals("ghost", error.roleName)
    }

    /**
     * Verifies that subject, role-name, and prompt omissions are rejected before role lookup.
     */
    @Test
    fun `build rejects missing or blank parameters`() = runTest {
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"agent_role_name":"x","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"subject":"","agent_role_name":"x","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"subject":"Task","agent_role_name":"","prompt":"hi"}""")).leftOrNull()
        )
        assertIs<SpawnRequestBuildError.InvalidInput>(
            builder.build(context(), toolCall(input = """{"subject":"Task","agent_role_name":"x"}""")).leftOrNull()
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
            """{"subject":["Task"],"agent_role_name":"implementer","prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_name":{"name":"implementer"},"prompt":"Do the thing"}""",
            """{"subject":"Task","agent_role_name":"implementer","prompt":{"text":"Do the thing"}}"""
        )

        invalidInputs.forEach { input ->
            val result = builder.build(context(), toolCall(input = input))

            assertIs<SpawnRequestBuildError.InvalidInput>(result.leftOrNull())
        }
    }
}