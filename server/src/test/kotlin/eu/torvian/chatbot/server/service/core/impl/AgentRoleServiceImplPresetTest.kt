package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests for [AgentRoleServiceImpl] attached-preset validation, resolution and derived values. */
class AgentRoleServiceImplPresetTest : AgentRoleServiceImplTestBase() {

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
