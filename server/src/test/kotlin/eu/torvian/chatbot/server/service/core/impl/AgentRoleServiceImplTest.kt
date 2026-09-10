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
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.AgentRoleDao.AgentRoleNameScope
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private lateinit var agentRoleDisabledDao: AgentRoleDisabledDao
    private lateinit var projectDao: ProjectDao
    private lateinit var sessionDao: SessionDao
    private lateinit var modelPresetDao: ModelPresetDao
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

    /**
     * The preset `validRequest()` attaches: model 1 plus the chat-capable settings profile 1. Its
     * references agree, which is the state every write path validates.
     */
    private val validPreset = TestDefaults.modelPreset1.copy(
        id = 1L,
        modelId = 1L,
        modelSettingsId = chatSettings.id
    )

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
        agentRoleDisabledDao = mockk()
        projectDao = mockk()
        sessionDao = mockk()
        modelPresetDao = mockk()
        settingsDao = mockk()
        toolDefinitionDao = mockk()
        transactionScope = mockk()

        service = AgentRoleServiceImpl(
            agentRoleDao = agentRoleDao,
            agentRoleToolDao = agentRoleToolDao,
            agentRoleSpawnableRoleDao = agentRoleSpawnableRoleDao,
            agentRoleOwnershipDao = agentRoleOwnershipDao,
            agentRoleDisabledDao = agentRoleDisabledDao,
            modelPresetDao = modelPresetDao,
            settingsDao = settingsDao,
            toolDefinitionDao = toolDefinitionDao,
            json = json,
            transactionScope = transactionScope,
            projectDao = projectDao,
            sessionDao = sessionDao
        )

        // The spawn allow-list DAO is always consulted (non-nullable dependency); default its reads
        // to empty and its writes to no-ops so tests focus on the behavior under test.
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(any()) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(any()) } returns emptyMap()
        coEvery { agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(any(), any()) } returns Unit

        // The disabled-markers DAO is likewise always consulted; default its reads to "enabled" for
        // both the batch and the single-role shapes, and its writes to no-ops unless a test asserts
        // specific behavior.
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(any(), any()) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(any(), any()) } returns false
        coEvery { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) } returns Unit

        // The preset DAO is consulted by every read path (single-role resolution and the list batch)
        // and by the role-attach validation. Default it to returning the valid preset so tests that do
        // not care about the configuration stay one-liners; specific tests override the stubs.
        coEvery { modelPresetDao.getPresetsByIdsForUser(any(), any()) } returns listOf(validPreset)
        coEvery { modelPresetDao.getPresetById(any()) } returns validPreset.right()
        // The preset's settings reference is loaded (and must be chat-capable) whenever the preset
        // carries one; default it to the matching chat profile.
        coEvery { settingsDao.getSettingsById(any()) } returns chatSettings.right()

        // Project-membership DAO: the role side of the membership rides the role entity (single
        // `project_id` column), so only the ownership/existence reads are defaulted here.
        coEvery { projectDao.getProjectsByIdsForUser(any(), any()) } returns emptyList()
        // The role-update sweep reads and writes; default the read to "no sessions use the role" and the
        // write to a no-op so existing update tests stay focused.
        coEvery { sessionDao.getSessionProjectPairsForRole(any()) } returns emptyList()
        coEvery { sessionDao.clearAgentRoleForSessions(any()) } returns Unit

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
            agentRoleDisabledDao,
            projectDao,
            sessionDao,
            modelPresetDao,
            settingsDao,
            toolDefinitionDao,
            transactionScope
        )
    }

    private fun validRequest() = CreateAgentRoleRequest(
        name = "Senior Architect",
        displayName = "Architect",
        description = "Designs systems",
        modelPresetId = validPreset.id,
        toolIds = emptySet(),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
        )
    )

    @Test
    fun `createRole should persist the role and set ownership on success`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
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
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user already owns a same-named role in the unassociated scope (empty project set), which
        // overlaps the candidate's unassociated scope under the scope-intersection rule.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 9L, projectId = null)
        )

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { agentRoleToolDao.replaceToolsForRole(any(), any()) }
    }

    @Test
    fun `createRole should allow reusing a name owned by another user`() = runTest {
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // Another user owns "Senior Architect"; the requesting user does not, so the name is free.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns emptyList()
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
    fun `createRole should reject an attached preset whose settings are not chat-capable`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        // The preset's settings reference points at a completion profile, which cannot drive a chat turn.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(
            validPreset.copy(modelSettingsId = completionSettings.id)
        )
        coEvery { settingsDao.getSettingsById(5L) } returns completionSettings.right()

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ModelPresetNotChatLike>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createRole should reject an attached preset whose ids disagree`() = runTest {
        // Reachable when the settings profile was re-pointed to another model after the preset was
        // written (RQ-1): the stored preset then violates the preset.modelId == settings.modelId rule.
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(validPreset)
        coEvery { settingsDao.getSettingsById(1L) } returns TestDefaults.modelSettings2.right()

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ModelPresetSettingsModelMismatch>(result.leftOrNull())
    }

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
    fun `createRole should succeed with a null modelPresetId`() = runTest {
        // A preset-less role is intentionally allowed (completed later via update). No preset or
        // settings lookup may run when no preset is attached.
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1.copy(modelPresetId = null)
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = validRequest().copy(modelPresetId = null)
        val result = service.createRole(userId, request)

        assertTrue(result.isRight())
        val dto = result.getOrNull()!!
        // A preset-less role reports no configuration at all: the derived ids are null too.
        assertEquals(null, dto.modelPresetId)
        assertEquals(null, dto.modelId)
        assertEquals(null, dto.modelSettingsId)
        coVerify(exactly = 0) { modelPresetDao.getPresetsByIdsForUser(any(), any()) }
        coVerify(exactly = 0) { settingsDao.getSettingsById(any()) }
    }

    @Test
    fun `createRole should accept a preset that has no model reference`() = runTest {
        // Only the non-null references of an attached preset are validated: a model-less preset is legal
        // (that is the state ON DELETE SET NULL produces) and simply yields a non-sendable role.
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        val settingsOnlyPreset = validPreset.copy(modelId = null)
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(settingsOnlyPreset)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1.copy(modelPresetId = 1L)
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        // The settings reference is still validated (existence + chat capability), and no
        // model↔settings agreement check runs because the preset has no model.
        coVerify(exactly = 1) { settingsDao.getSettingsById(1L) }
    }

    @Test
    fun `createRole should accept a preset with null references`() = runTest {
        // OQ-1 = b: a preset whose model AND settings references are both null (the fully SET NULL'd
        // state) may be attached; the resulting role is non-sendable, which turn preparation reports.
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        val emptyPreset = validPreset.copy(modelId = null, modelSettingsId = null)
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(emptyPreset)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1.copy(modelPresetId = 1L)
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        coVerify(exactly = 0) { settingsDao.getSettingsById(any()) }
    }

    @Test
    fun `createRole should reject a missing or foreign preset`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        // The owner-scoped batch read omits a foreign/missing id, which collapses to the same error.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns emptyList()

        val result = service.createRole(userId, validRequest())

        val error = assertIs<CreateAgentRoleError.ModelPresetNotFound>(result.leftOrNull())
        assertEquals(1L, error.presetId)
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createRole should fail technically when an attached preset's settings row vanished`() = runTest {
        // The preset's non-null settings reference always resolves on a runtime connection (FK
        // enforcement), so a not-found here is a technical failure, not a logical error: it must surface
        // as an exception rather than a typed 4xx (Arrow errors must not model technical failures).
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(validPreset)
        coEvery { settingsDao.getSettingsById(1L) } returns
            eu.torvian.chatbot.server.data.dao.error.SettingsError.SettingsNotFound(1L).left()

        assertFailsWith<IllegalStateException> { service.createRole(userId, validRequest()) }
    }

    @Test
    fun `updateRole should succeed when detaching the preset`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelPresetId = null,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // Detaching is a plain null: no preset or settings lookup is needed for it.
        coVerify(exactly = 0) { modelPresetDao.getPresetsByIdsForUser(any(), any()) }
        coVerify(exactly = 0) { settingsDao.getSettingsById(any()) }
        coVerify(exactly = 1) { agentRoleDao.updateRole(match { it.modelPresetId == null }) }
    }

    @Test
    fun `createRole should reject a tool id that does not exist`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns no tools at all, so any attached id is missing/foreign.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns emptyList()

        val request = validRequest().copy(toolIds = setOf(99L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject a server built-in tool owned by another user`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns their own server built-in row (54L) but not the foreign 55L.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            ServerBuiltInToolDefinition(
                id = 54L,
                name = "list_agent_roles",
                description = "Lists agent roles",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = null,
                isEnabled = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                userId = userId,
                builtInToolName = "list_agent_roles"
            )
        )

        val request = validRequest().copy(toolIds = setOf(55L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject an MCP tool of another user's server`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns MCP tool 1L (their own server); 2L belongs to another user's server.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            LocalMCPToolDefinition(
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
        )

        val request = validRequest().copy(toolIds = setOf(2L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject a built-in tool of another user's worker`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns worker built-in 3L (their own worker); 4L belongs to another user's worker.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            BuiltInWorkerToolDefinition(
                id = 3L,
                name = "read_text_file",
                description = "Reads a file",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = null,
                isEnabled = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                workerId = 1L,
                builtInToolName = "read_text_file"
            )
        )

        val request = validRequest().copy(toolIds = setOf(4L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }

    @Test
    fun `updateRole should persist the tool set via the join table on success`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
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
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(tool, tool.copy(id = 2L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelPresetId = validPreset.id,
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

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleToolDao.getToolsForRole(any()) }
    }

    @Test
    fun `updateRole should reject an attached preset whose settings are not chat-capable`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        // The preset's settings reference points at a completion profile, which cannot drive a chat turn.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(1L)) } returns listOf(
            validPreset.copy(modelSettingsId = completionSettings.id)
        )
        coEvery { settingsDao.getSettingsById(5L) } returns completionSettings.right()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isLeft())
        assertIs<UpdateAgentRoleError.ModelPresetNotChatLike>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }

    @Test
    fun `deleteRole should delete an owned role`() = runTest {
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleDao.deleteRole(1L) } returns Unit.right()

        val result = service.deleteRole(userId, 1L)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleDao.deleteRole(1L) }
    }

    @Test
    fun `deleteRole should reject a role owned by another user`() = runTest {
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns 99L.right()

        val result = service.deleteRole(userId, 1L)

        // A foreign role collapses into NotFound (no existence leak) and is never deleted.
        val error = assertIs<DeleteAgentRoleError.NotFound>(result.leftOrNull())
        assertEquals(1L, error.id)
        coVerify(exactly = 0) { agentRoleDao.deleteRole(any()) }
    }

    @Test
    fun `deleteRole should reject a nonexistent role`() = runTest {
        coEvery {
            agentRoleOwnershipDao.getOwner(1L)
        } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.deleteRole(userId, 1L)

        val error = assertIs<DeleteAgentRoleError.NotFound>(result.leftOrNull())
        assertEquals(1L, error.id)
        coVerify(exactly = 0) { agentRoleDao.deleteRole(any()) }
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

    @Test
    fun `getAllRolesForUser maps the per-user disabled flag and resolves presets in one batch read`() = runTest {
        val roleWithPreset = TestDefaults.agentRole1.copy(modelPresetId = validPreset.id)
        val roleWithoutPreset = TestDefaults.agentRole2.copy(modelPresetId = null)
        coEvery { agentRoleDao.getAllRolesForUser(userId) } returns listOf(roleWithPreset, roleWithoutPreset)
        coEvery { agentRoleToolDao.getToolsForRoles(listOf(1L, 2L)) } returns mapOf(1L to emptySet(), 2L to emptySet())
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(listOf(1L, 2L)) } returns emptyMap()
        // Role 1 is disabled for the user; role 2 is not. The service must resolve both flags from the
        // single batch DAO read (no N+1 per role).
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(userId, listOf(1L, 2L)) } returns setOf(1L)
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(validPreset.id)) } returns listOf(validPreset)

        val roles = service.getAllRolesForUser(userId)

        assertEquals(2, roles.size)
        assertTrue(roles[0].disabled)
        assertFalse(roles[1].disabled)
        // The preset is resolved for the whole list in ONE batch read (NFR-5 / no N+1), and the
        // preset-bound role reports the preset's ids as derived values while the preset-less role
        // reports none.
        coVerify(exactly = 1) { modelPresetDao.getPresetsByIdsForUser(userId, listOf(validPreset.id)) }
        assertEquals(validPreset.id, roles[0].modelPresetId)
        assertEquals(validPreset.modelId, roles[0].modelId)
        assertEquals(validPreset.modelSettingsId, roles[0].modelSettingsId)
        assertEquals(null, roles[1].modelPresetId)
        assertEquals(null, roles[1].modelId)
        assertEquals(null, roles[1].modelSettingsId)
        coVerify(exactly = 1) { agentRoleDisabledDao.getDisabledRoleIds(userId, listOf(1L, 2L)) }
    }

    @Test
    fun `getRoleById reports disabled for the requesting user when a side-table row exists`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userId, 1L) } returns true

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
    }

    @Test
    fun `disabled state is isolated per user for the same role`() = runTest {
        // userA disabled role 1; the service must keep userB's reads enabled for the same role. The
        // domain load (`getAgentRoleById`) is the requester-scoped path: the role row is resolved by
        // id, ownership stays with userA, and only the disabled flag varies with the requesting user
        // (exactly what future shared roles need).
        val userA = 7L
        val userB = 8L
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userA.right()
        coEvery { agentRoleDao.getRoleByNameForUser(userA, "Senior Architect", null) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userA, 1L) } returns true
        coEvery { agentRoleDisabledDao.isRoleDisabled(userB, 1L) } returns false

        val dtoForA = service.getRoleById(userA, 1L).getOrNull()!!
        val byNameForA = service.getRoleByName(userA, "Senior Architect").getOrNull()!!
        val domainForA = service.getAgentRoleById(userA, 1L).getOrNull()!!
        val domainForB = service.getAgentRoleById(userB, 1L).getOrNull()!!

        assertTrue(dtoForA.disabled)
        assertTrue(byNameForA.disabled)
        assertTrue(domainForA.disabled)
        assertFalse(domainForB.disabled)
    }

    @Test
    fun `getAgentRoleById resolves the per-user disabled flag on the domain role`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userId, 1L) } returns true

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
        coVerify(exactly = 1) { agentRoleDisabledDao.isRoleDisabled(userId, 1L) }
    }

    @Test
    fun `createRole returns a role enabled for the new owner`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        assertFalse(result.getOrNull()!!.disabled)
        // No disabled marker is consulted or written for a fresh role.
        coVerify(exactly = 0) { agentRoleDisabledDao.getDisabledRoleIds(any(), any()) }
        coVerify(exactly = 0) { agentRoleDisabledDao.isRoleDisabled(any(), any()) }
        coVerify(exactly = 0) { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) }
    }

    @Test
    fun `setRoleDisabled disables an owned role and returns the updated DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.setRoleDisabled(userId, 1L, disabled = true)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
        coVerify(exactly = 1) { agentRoleDisabledDao.setRoleDisabled(userId, 1L, true) }
    }

    @Test
    fun `setRoleDisabled re-enables idempotently and returns an enabled DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.setRoleDisabled(userId, 1L, disabled = false)

        assertTrue(result.isRight())
        assertFalse(result.getOrNull()!!.disabled)
        // Same idempotent write pattern regardless of the current side-table state.
        coVerify(exactly = 1) { agentRoleDisabledDao.setRoleDisabled(userId, 1L, false) }
    }

    @Test
    fun `setRoleDisabled rejects a foreign role without writing`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.setRoleDisabled(userId, 1L, disabled = true)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) }
    }

    // --- Project membership ---

    @Test
    fun `createRole persists projectId atomically with the role row and returns it on the DTO`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The requesting user owns the referenced project.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.project1)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(projectId = 1L))

        assertTrue(result.isRight())
        assertEquals(1L, result.getOrNull()!!.projectId)
        // The single membership column is written together with the row, atomically with the tools
        // and ownership writes.
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), eq(1L))
        }
    }

    @Test
    fun `createRole rejects a foreign project id as not found`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user owns no project at all, so every attached id is missing/foreign.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val result = service.createRole(userId, validRequest().copy(projectId = 99L))

        val error = assertIs<CreateAgentRoleError.ProjectNotFound>(result.leftOrNull())
        assertEquals(99L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createRole rejects a same-name unassociated role (scope-intersection rule)`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = null)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()

        val result = service.createRole(userId, validRequest())

        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createRole rejects a same-name role sharing a project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)

        val result = service.createRole(userId, validRequest().copy(projectId = 1L))

        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
    }

    @Test
    fun `createRole allows a same-name role in a disjoint project`() = runTest {
        // The existing "Senior Architect" lives in project 1 only; the candidate joins project 2 only
        // — non-overlapping scopes, same user, same name: allowed.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(projectId = 2L))

        assertTrue(result.isRight())
        // The single membership column is written together with the row.
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), eq(2L))
        }
    }

    @Test
    fun `createRole allows an unassociated role next to a same-name in-project role`() = runTest {
        // The existing "Senior Architect" belongs to project 1; the candidate is unassociated (empty
        // set) — unassociated scope vs project scope never conflict.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
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
    fun `updateRole re-checks the name when only the project changed (self excluded)`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role currently belongs to project 1; the update moves it to project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        // A DIFFERENT role (5L) already occupies project 2 with the same name -> scope overlap, reject.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Renamed") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 2L)
        )
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            ),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertIs<UpdateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }

    @Test
    fun `updateRole re-checking the scope excludes the role being updated`() = runTest {
        // The role currently lives in project 1; the update drops it to the unassociated scope, which
        // still changes the scope, so the recheck runs; the same-name row returned by the scope query
        // IS the role itself, which must not conflict with itself.
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 1L, projectId = 1L)
        )
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Senior Architect",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // The row update ran even though the scope query returned the role itself.
        coVerify(exactly = 1) { agentRoleDao.updateRole(any()) }
    }

    @Test
    fun `updateRole persists the new projectId and returns it on the DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role moves from project 1 to project 2; the membership is rewritten with the row.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            ),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        assertEquals(2L, result.getOrNull()!!.projectId)
        coVerify(exactly = 1) { agentRoleDao.updateRole(match { it.projectId == 2L }) }
    }

    @Test
    fun `updateRole rejects a foreign project id as not found`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user owns no project matching id 99.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 99L
        )
        val result = service.updateRole(userId, 1L, request)

        val error = assertIs<UpdateAgentRoleError.ProjectNotFound>(result.leftOrNull())
        assertEquals(99L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }

    @Test
    fun `updateRole clears the role on sessions whose project differs from the new projectId`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role currently belongs to project 1; the update moves it to project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        // Session 10 selected the role's OLD project 1 -> now illegal; session 11 selected the new
        // project 2 -> legal; session 12 has no project but the role is now project-bound -> illegal.
        coEvery { sessionDao.getSessionProjectPairsForRole(1L) } returns listOf(
            SessionProjectPair(sessionId = 10L, projectId = 1L),
            SessionProjectPair(sessionId = 11L, projectId = 2L),
            SessionProjectPair(sessionId = 12L, projectId = null)
        )

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // Only the sessions whose project differs from the new single project id are cleared.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(listOf(10L, 12L)) }
    }

    @Test
    fun `updateRole keeps sessions when the new projectId still covers them`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { sessionDao.getSessionProjectPairsForRole(1L) } returns listOf(
            SessionProjectPair(sessionId = 11L, projectId = 1L)
        )

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 1L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // Session 11 occupies exactly the role's project: no sweep clears anything.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }

    // --- Spawn allow-list same-project enforcement ---

    @Test
    fun `createRole rejects a spawnable target in a different project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The source role joins project 1; the spawn target lives in project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 2L))

        val result = service.createRole(userId, validRequest().copy(projectId = 1L, spawnableAgentRoleIds = setOf(5L)))

        val error = assertIs<CreateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(1L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createRole rejects a spawnable target when the source is unassociated and the target is in a project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The source role stays unassociated (projectId null); the target belongs to project 1.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 1L))

        val result = service.createRole(userId, validRequest().copy(spawnableAgentRoleIds = setOf(5L)))

        val error = assertIs<CreateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(null, error.projectId)
    }

    @Test
    fun `createRole accepts spawn targets sharing the role's project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        // Both targets are unassociated like the source role -> legal.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L, 6L)) } returns listOf(
            TestDefaults.agentRole1.copy(id = 5L),
            TestDefaults.agentRole2.copy(id = 6L)
        )
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(spawnableAgentRoleIds = setOf(5L, 6L)))

        assertTrue(result.isRight())
    }

    @Test
    fun `updateRole exempts self-spawn when the role moves projects`() = runTest {
        // The role currently lives in project 1 and moves to project 2 while keeping itself in its
        // spawn allow-list: the persisted (stale) membership of the target IS the role being written,
        // so the same-project check must exempt it (its row is updated with projectId 2 in the same
        // transaction).
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1.copy(projectId = 1L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            spawnableAgentRoleIds = setOf(1L),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleDao.updateRole(match { it.projectId == 2L }) }
    }

    @Test
    fun `updateRole rejects a spawnable target outside the role's new project`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        // The target role stays in project 1 while the source moves to project 2 -> illegal.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 1L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            spawnableAgentRoleIds = setOf(5L),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        val error = assertIs<UpdateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(2L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }

    // --- Model preset resolution (derived values) ---

    @Test
    fun `createRole attaches a chat-capable preset and reports the derived model and settings ids`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        // The stored row carries only the preset reference; the DTO's model/settings ids are derived
        // from the preset resolved during validation (no second read).
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1.copy(modelPresetId = validPreset.id)
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        val dto = result.getOrNull()!!
        assertEquals(validPreset.id, dto.modelPresetId)
        assertEquals(validPreset.modelId, dto.modelId)
        assertEquals(validPreset.modelSettingsId, dto.modelSettingsId)
        // The row write carries the preset reference and NOTHING else from the configuration (the
        // modelPresetId argument is the 4th one: name, displayName, description, modelPresetId, ...).
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(any(), any(), any(), match { it == validPreset.id }, any(), isNull())
        }
        // The preset is resolved once for validation and reused for the echoed DTO.
        coVerify(exactly = 1) { modelPresetDao.getPresetsByIdsForUser(userId, listOf(validPreset.id)) }
    }

    @Test
    fun `getRoleById resolves the attached preset into the derived DTO fields`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns
            TestDefaults.agentRole1.copy(modelPresetId = validPreset.id).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isRight())
        val dto = result.getOrNull()!!
        assertEquals(validPreset.id, dto.modelPresetId)
        assertEquals(validPreset.modelId, dto.modelId)
        assertEquals(validPreset.modelSettingsId, dto.modelSettingsId)
        coVerify(exactly = 1) { modelPresetDao.getPresetById(validPreset.id) }
    }

    @Test
    fun `getAgentRoleById resolves the preset so model_specific instructions can be selected`() = runTest {
        // The composer selects model_specific instructions by the domain role's modelId, which is now
        // derived from the preset: the read must therefore resolve the preset (AC-8).
        coEvery { agentRoleDao.getRoleById(1L) } returns
            TestDefaults.agentRole1.copy(modelPresetId = validPreset.id).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isRight())
        val role = result.getOrNull()!!
        assertEquals(validPreset.id, role.modelPresetId)
        assertEquals(validPreset.modelId, role.modelId)
        assertEquals(validPreset.modelSettingsId, role.modelSettingsId)
    }

    @Test
    fun `reads degrade to no configuration when the referenced preset row is dangling`() = runTest {
        // A non-null model_preset_id always resolves on a runtime connection (FK enforcement), so a
        // missing row is a database inconsistency: reads log it and report no configuration instead of
        // failing, while turn preparation still refuses to run the role.
        coEvery { agentRoleDao.getRoleById(1L) } returns
            TestDefaults.agentRole1.copy(modelPresetId = 99L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { modelPresetDao.getPresetById(99L) } returns
            eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError.NotFound(99L).left()

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isRight())
        val dto = result.getOrNull()!!
        assertEquals(null, dto.modelId)
        assertEquals(null, dto.modelSettingsId)
    }
}
