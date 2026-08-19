package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.llm.CompletionModelSettings
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Unit tests for [AgentRoleServiceImpl] validation and ownership behavior.
 */
class AgentRoleServiceImplTest {

    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var agentRoleToolDao: AgentRoleToolDao
    private lateinit var agentRoleOwnershipDao: AgentRoleOwnershipDao
    private lateinit var agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao
    private lateinit var modelDao: ModelDao
    private lateinit var settingsDao: SettingsDao
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var service: AgentRoleServiceImpl

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private val chatSettings = TestDefaults.modelSettings1.copy(id = 1L, modelId = 1L)

    private val completionSettings = CompletionModelSettings(
        id = 5L,
        modelId = 1L,
        name = "Completion",
        suffix = null,
        temperature = null,
        maxTokens = null,
        topP = null,
        stopSequences = null,
        customParams = null
    )

    @BeforeEach
    fun setUp() {
        agentRoleDao = mockk()
        agentRoleToolDao = mockk()
        agentRoleOwnershipDao = mockk()
        agentRoleSpawnableRoleDao = mockk()
        modelDao = mockk()
        settingsDao = mockk()
        toolDefinitionDao = mockk()
        transactionScope = mockk()

        service = AgentRoleServiceImpl(
            agentRoleDao = agentRoleDao,
            agentRoleToolDao = agentRoleToolDao,
            agentRoleSpawnableRoleDao = agentRoleSpawnableRoleDao,
            agentRoleOwnershipDao = agentRoleOwnershipDao,
            modelDao = modelDao,
            settingsDao = settingsDao,
            toolDefinitionDao = toolDefinitionDao,
            json = json,
            transactionScope = transactionScope
        )

        // The spawn allow-list DAO is always consulted (non-nullable dependency); default its reads
        // to empty and its writes to no-ops so tests focus on the behavior under test.
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(any()) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(any()) } returns emptyMap()
        coEvery { agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(any(), any()) } returns Unit

        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            agentRoleDao,
            agentRoleToolDao,
            agentRoleOwnershipDao,
            agentRoleSpawnableRoleDao,
            modelDao,
            settingsDao,
            toolDefinitionDao,
            transactionScope
        )
    }

    private fun validRequest() = CreateAgentRoleRequest(
        name = "Senior Architect",
        displayName = "Architect",
        description = "Designs systems",
        modelId = 1L,
        modelSettingsId = 1L,
        toolIds = emptySet(),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
        )
    )

    @Test
    fun `createRole should persist the role and set ownership on success`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        // The returned DTO loads the role's tools from the join table.
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals("Senior Architect", dto.name)
        // instructions are resolved to DTOs on return
        assertEquals(1, dto.instructions.size)
        assertEquals(AgentInstructionTypes.ROLE, dto.instructions[0].type)
        coVerify(exactly = 1) { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) }
        // The tool set is persisted into the join table (full replacement of the new role's empty set).
        coVerify(exactly = 1) { agentRoleToolDao.replaceToolsForRole(TestDefaults.agentRole1.id, emptySet()) }
    }

    @Test
    fun `createRole should reject a duplicate name for the same user`() = runTest {
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user already owns a role with this name (per-user uniqueness, enforced by the service).
        coEvery { agentRoleDao.roleNameExistsForUser(userId, "Senior Architect") } returns true

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { agentRoleToolDao.replaceToolsForRole(any(), any()) }
    }

    @Test
    fun `createRole should allow reusing a name owned by another user`() = runTest {
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // Another user owns "Senior Architect"; the requesting user does not, so the name is free.
        coEvery { agentRoleDao.roleNameExistsForUser(userId, "Senior Architect") } returns false
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
    }

    @Test
    fun `createRole should reject non-chat-capable settings`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(5L) } returns completionSettings.right()

        val request = validRequest().copy(modelSettingsId = 5L)
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.SettingsNotChatLike>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject settings belonging to a different model`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(2L) } returns TestDefaults.modelSettings2.right()

        val request = validRequest().copy(modelSettingsId = 2L)
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.SettingsModelMismatch>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject duplicate singleton instructions`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
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
    fun `createRole should reject a missing tool reference`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            toolDefinitionDao.getToolDefinitionById(99L)
        } returns eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError.NotFound(99L).left()

        val request = validRequest().copy(toolIds = setOf(99L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }

    @Test
    fun `updateRole should persist the tool set via the join table on success`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        val tool = LocalMCPToolDefinition(
            id = 1L,
            name = "tool",
            description = "A tool",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            serverId = 1L,
            mcpToolName = "tool"
        )
        coEvery { toolDefinitionDao.getToolDefinitionById(1L) } returns tool.right()
        coEvery { toolDefinitionDao.getToolDefinitionById(2L) } returns tool.copy(id = 2L).right()
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelId = 1L,
            modelSettingsId = 1L,
            toolIds = setOf(1L, 2L),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // The update rewrites the whole tool set (full-replacement semantics preserved).
        coVerify(exactly = 1) { agentRoleToolDao.replaceToolsForRole(1L, setOf(1L, 2L)) }
    }

    @Test
    fun `getRoleById should return NotFound for a role owned by another user`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
    }

    @Test
    fun `getAgentRoleById should return NotFound when the ownership row is missing`() = runTest {
        // A role row without an ownership row is a database inconsistency; the unscoped domain load
        // must surface it as not-found instead of degrading the owner id to 0 (which would silently
        // produce empty spawn allow-list prompts).
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.getAgentRoleById(1L)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleToolDao.getToolsForRole(any()) }
    }

    @Test
    fun `updateRole should reject when settings become non-chat-capable`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
        coEvery { settingsDao.getSettingsById(5L) } returns completionSettings.right()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelId = 1L,
            modelSettingsId = 5L,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isLeft())
        assertIs<UpdateAgentRoleError.SettingsNotChatLike>(result.leftOrNull())
    }

    @Test
    fun `deleteRole should delete an owned role`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleDao.deleteRole(1L) } returns Unit.right()

        val result = service.deleteRole(userId, 1L)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleDao.deleteRole(1L) }
    }

    @Test
    fun `createRole should preserve model_specific instructions with their model ids`() = runTest {
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
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
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
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
        coEvery { agentRoleDao.roleNameExistsForUser(any(), any()) } returns false
        coEvery { modelDao.getModelById(1L) } returns TestDefaults.llmModel1.right()
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

        val result = service.getAgentRoleById(1L)

        assertTrue(result.isRight())
        val role = result.getOrNull()!!
        assertEquals(2, role.instructions.size)
        assertIs<RoleInstruction>(role.instructions[0])
        val modelSpecific = role.instructions[1]
        assertIs<ModelSpecificInstruction>(modelSpecific)
        assertEquals(2L, modelSpecific.modelId)
    }
}
