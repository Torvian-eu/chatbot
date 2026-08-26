package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [InsertAgentRoleInstructionTool].
 *
 * Covers the load-insert-update flow (position splice, append-at-end), input validation (missing
 * parameters, out-of-range position, per-type message rules), ownership-collapsed not-found, and
 * the passthrough of shared instruction-list validation failures from the role service.
 */
class InsertAgentRoleInstructionToolTest {

    private val userId = 7L

    private fun sampleRole() = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer."),
            AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "Write clean code.")
        )
    )

    private fun customInstruction() = buildJsonObject {
        put("type", AgentInstructionTypes.CUSTOM)
        put("name", "Tone")
        put("message", "Be concise.")
    }

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires role_id position and instruction`() = runTest {
        val tool = InsertAgentRoleInstructionTool(mockk())

        val result = tool.execute(userId, buildJsonObject { })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: role_id"))
        assertTrue(error.message.contains("Missing required argument: position"))
        assertTrue(error.message.contains("Missing required argument: instruction"))
    }

    @Test
    fun `inserts the instruction at the given position preserving every other field`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 1L)
                    put("instruction", customInstruction())
                }
            )
        )

        // The tool returns a concise one-line operation summary naming the inserted instruction,
        // not the full role JSON (the full role is available via read_agent_role).
        assertTrue(output.contains("Inserted instruction (type=custom, name=Tone) at 0-based position 1"))
        assertTrue(!output.contains("\"id\":1"))

        // The new instruction is spliced at index 1, and every other role field is carried over
        // unchanged from the persisted role.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.size == 3 &&
                        request.instructions[0] == persisted.instructions[0] &&
                        request.instructions[1].type == AgentInstructionTypes.CUSTOM &&
                        request.instructions[1].message == "Be concise." &&
                        request.instructions[2] == persisted.instructions[1] &&
                        request.name == persisted.name &&
                        request.modelId == persisted.modelId &&
                        request.toolIds == persisted.tools &&
                        request.spawnableAgentRoleIds == persisted.spawnableAgentRoleIds
                }
            )
        }
    }

    @Test
    fun `position equal to the list size appends at the end`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 2L)
                    put("instruction", customInstruction())
                }
            )
        )
        assertTrue(output.contains("0-based position 2"))

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.size == 3 && request.instructions.last().message == "Be concise."
                }
            )
        }
    }

    @Test
    fun `inserts into a role with no instructions`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole().copy(instructions = emptyList())
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 0L)
                    put("instruction", customInstruction())
                }
            )
        )
        assertTrue(output.contains("at 0-based position 0"))

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.size == 1 &&
                        request.instructions.single().type == AgentInstructionTypes.CUSTOM &&
                        request.instructions.single().message == "Be concise."
                }
            )
        }
    }

    @Test
    fun `passes the custom object through for model_specific instructions`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns sampleRole().right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 0L)
                    put("instruction", buildJsonObject {
                        put("type", AgentInstructionTypes.MODEL_SPECIFIC)
                        put("name", "Swift mode")
                        put("message", "Write idiomatic Swift")
                        putJsonObject("custom") {
                            put("modelId", 2L)
                        }
                    })
                }
            )
        )
        assertTrue(
            output.contains("Inserted instruction (type=model_specific, name=Swift mode) at 0-based position 0")
        )

        // The custom JSON survives parsing verbatim: the role service later validates that the
        // referenced model exists and that each model_specific target is distinct.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.first().type == AgentInstructionTypes.MODEL_SPECIFIC &&
                        request.instructions.first().modelSpecificId() == 2L
                }
            )
        }
    }

    @Test
    fun `rejects an out-of-range position without updating`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 5L)
                put("instruction", customInstruction())
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("position"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects a missing message for non-spawnable instruction types`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("instruction", buildJsonObject {
                    put("type", AgentInstructionTypes.ROLE)
                    put("name", "Role")
                })
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("message"))
        // The malformed instruction is rejected before any role load.
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `requires an empty message for spawnable_agents instructions`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 0L)
                    put("instruction", buildJsonObject {
                        put("type", AgentInstructionTypes.SPAWNABLE_AGENTS)
                        put("name", "Spawn")
                        put("message", "")
                    })
                }
            )
        )
        assertTrue(output.contains("Inserted instruction (type=spawnable_agents, name=Spawn) at 0-based position 0"))

        // spawnable_agents instructions take an empty message; the server regenerates the
        // message from the role's spawn allow-list at read time.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.first().type == AgentInstructionTypes.SPAWNABLE_AGENTS &&
                        request.instructions.first().message == ""
                }
            )
        }
    }

    @Test
    fun `rejects an unknown instruction type`() = runTest {
        val tool = InsertAgentRoleInstructionTool(mockk())

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("instruction", buildJsonObject {
                    put("type", "bogus")
                    put("name", "Role")
                    put("message", "x")
                })
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("'type' must be one of"))
    }

    @Test
    fun `collapses not-found and not-accessible`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 99L)
                put("position", 0L)
                put("instruction", customInstruction())
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects unknown keys inside the instruction object`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        // The catalog schema advertises additionalProperties: false for the instruction object, so
        // a stray key (here a typo of the old proposal's "custom_properties" shape) is rejected
        // instead of being silently dropped from the persisted role.
        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("instruction", buildJsonObject {
                    put("type", AgentInstructionTypes.CUSTOM)
                    put("name", "Tone")
                    put("message", "Be concise.")
                    put("custom_properties", buildJsonObject { put("model_id", 5) })
                })
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'custom_properties' in instruction object"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("instruction", customInstruction())
                put("custom_properties", buildJsonObject { put("model_id", 5) })
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'custom_properties'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `surfaces instruction validation failures from the role service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns
            UpdateAgentRoleError.InstructionValidationFailed("At most one 'role' instruction is allowed").left()
        val tool = InsertAgentRoleInstructionTool(agentRoleService)

        // Inserting a second 'role' instruction violates the shared instruction-list rules.
        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("instruction", buildJsonObject {
                    put("type", AgentInstructionTypes.ROLE)
                    put("name", "Role 2")
                    put("message", "Second role.")
                })
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("instruction_validation_failed", error.code)
    }
}
