package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests for [AgentRoleServiceImpl] instruction list rules and instruction mapping. */
class AgentRoleServiceImplInstructionTest : AgentRoleServiceImplTestBase() {

    @Test
    fun `createRole should reject duplicate singleton instructions`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()

        val request = validRequest().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role 1", "One"),
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role 2", "Two")
            )
        )
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
    }
    @Test
    fun `createRole should preserve model_specific instructions with their model ids`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1.copy(
            instructionsJson = """
                [
                    {"type":"role","name":"Role","message":"You are a senior architect."},
                    {"type":"model_specific","name":"Swift mode","message":"Write idiomatic Swift","custom":{"modelId":2}}
                ]
            """.trimIndent()
        )
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = validRequest().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect."),
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "Swift mode", "Write idiomatic Swift",
                    custom = buildJsonObject { put("modelId", 2L) })
            )
        )
        val result = service.createRole(userId, request)

        assertTrue(result.isRight())
        val dto = result.getOrNull()!!
        assertEquals(2, dto.instructions.size)
        val modelSpecific = dto.instructions[1]
        assertEquals(AgentInstructionTypes.MODEL_SPECIFIC, modelSpecific.type)
        assertEquals(2L, modelSpecific.modelSpecificId())
        assertEquals("Write idiomatic Swift", modelSpecific.message)
    }
    @Test
    fun `createRole should accept multiple model_specific instructions with distinct models`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = validRequest().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "A", "msg a",
                    custom = buildJsonObject { put("modelId", 2L) }),
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "B", "msg b",
                    custom = buildJsonObject { put("modelId", 3L) })
            )
        )
        val result = service.createRole(userId, request)

        assertTrue(result.isRight())
    }
    @Test
    fun `createRole should reject duplicate model_specific target models`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()

        val request = validRequest().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "A", "msg a",
                    custom = buildJsonObject { put("modelId", 2L) }),
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "B", "msg b",
                    custom = buildJsonObject { put("modelId", 2L) })
            )
        )
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
    }
    @Test
    fun `createRole should reject a model_specific instruction without a model id`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()

        // A model_specific instruction without custom.modelId would be silently dropped at read
        // time (the composer keeps only the instance matching the active model), so it is rejected.
        val request = validRequest().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "A", "msg a")
            )
        )
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        val error = assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        assertTrue(error.reason.contains("custom.modelId"))
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }
    @Test
    fun `getAgentRoleById maps stored kinds into domain subtypes`() = runTest {
        val entity = TestDefaults.agentRole1.copy(
            instructionsJson = """
                [
                    {"type":"role","name":"Role","message":"You are a senior architect."},
                    {"type":"model_specific","name":"Swift mode","message":"Write Swift","custom":{"modelId":2}}
                ]
            """.trimIndent()
        )
        coEvery { agentRoleDao.getRoleById(1L) } returns entity.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isRight())
        val role = result.getOrNull()!!
        assertEquals(2, role.instructions.size)
        assertIs<RoleInstruction>(role.instructions[0])
        val modelSpecific = role.instructions[1]
        assertIs<ModelSpecificInstruction>(modelSpecific)
        assertEquals(2L, modelSpecific.modelId)
    }
}
