package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.service.core.agent.CustomInstruction
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
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
import kotlin.time.Instant

/**
 * Tests for [AgentRoleServiceImpl] instruction specs: the per-role rules evaluated over the effective
 * list, the link-level uniqueness and ownership checks of a role payload, the full-replacement link
 * rewrite in request order, inline create/update materialization, and the echoed identity fields.
 *
 * A link-only payload references rows and never touches their content; inline specs are materialized
 * with their ownership inside the same transaction as the role write.
 */
class AgentRoleServiceImplInstructionTest : AgentRoleServiceImplTestBase() {

    /**
     * Builds a valid update request carrying the given instruction specs in list order.
     *
     * @param instructionSpecs The requested instruction specs in list order.
     * @return The update request.
     */
    private fun validUpdateRequest(instructionSpecs: List<InstructionSlot>) = UpdateAgentRoleRequest(
        name = "Senior Architect",
        displayName = "Architect",
        description = "Designs systems",
        modelPresetId = validPreset.id,
        toolIds = emptySet(),
        instructionSpecs = instructionSpecs
    )

    /**
     * Builds plain reference slots for the given stored rows.
     *
     * @param ids The row ids to reference, in the role's order.
     * @return The link slots.
     */
    private fun linkSlots(vararg ids: Long): List<InstructionSlot> = ids.map { InstructionSlot.Link(it) }

    /**
     * Stubs the owner-scoped instruction lookup with the given rows.
     *
     * @param rows The rows the lookup should report for any requested id set.
     */
    private fun stubOwnedInstructions(vararg rows: TestInstructionRow) {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, any()) } returns rows.map { it.toEntity() }
    }

    /**
     * A referenced instruction row as a test fixture describes it.
     *
     * @property id Row identifier.
     * @property type Instruction kind.
     * @property custom Stored `custom` JSON text, or null.
     */
    private data class TestInstructionRow(
        val id: Long,
        val type: String,
        val custom: String? = null
    ) {
        /**
         * Expands the fixture into the stored entity shape.
         *
         * @return The instruction entity the DAO would report.
         */
        fun toEntity() = TestDefaults.instruction1.copy(
            id = id,
            type = type,
            name = "Instruction $id",
            message = "Text",
            custom = custom
        )
    }

    @Test
    fun `createRole should reject two singleton instructions of the same kind`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        stubOwnedInstructions(
            TestInstructionRow(5L, AgentInstructionTypes.ROLE),
            TestInstructionRow(6L, AgentInstructionTypes.ROLE)
        )

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L, 6L)))

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
    }

    @Test
    fun `createRole should accept model_specific instructions with distinct target models`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(
            TestInstructionRow(5L, AgentInstructionTypes.MODEL_SPECIFIC, """{"modelId":2}"""),
            TestInstructionRow(6L, AgentInstructionTypes.MODEL_SPECIFIC, """{"modelId":3}""")
        )

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L, 6L)))

        assertTrue(result.isRight())
    }

    @Test
    fun `createRole should reject model_specific rows targeting the same model`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        stubOwnedInstructions(
            TestInstructionRow(5L, AgentInstructionTypes.MODEL_SPECIFIC, """{"modelId":2}"""),
            TestInstructionRow(6L, AgentInstructionTypes.MODEL_SPECIFIC, """{"modelId":2}""")
        )

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L, 6L)))

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
    }

    @Test
    fun `createRole should reject a model_specific row without a model id`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.MODEL_SPECIFIC, """{"wrong":2}"""))

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L)))

        assertTrue(result.isLeft())
        val error = assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        assertTrue(error.reason.contains("custom.modelId"))
    }

    @Test
    fun `createRole rejects the same instruction id twice in one request`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L, 5L)))

        val error = assertIs<CreateAgentRoleError.DuplicateInstructionLink>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    @Test
    fun `createRole rejects a missing or foreign instruction id as not found`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // A missing id and one owned by somebody else collapse to the same outcome: the owner-scoped
        // batch read omits both.
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L)))

        val error = assertIs<CreateAgentRoleError.InstructionNotFound>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
    }

    @Test
    fun `createRole links exactly the requested rows in request order`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(
            TestInstructionRow(5L, AgentInstructionTypes.CUSTOM),
            TestInstructionRow(7L, AgentInstructionTypes.CUSTOM)
        )

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(7L, 5L)))

        assertTrue(result.isRight())
        // The payload order is the role's order, and row sequence is rewritten as a contiguous list.
        coVerify(exactly = 1) {
            agentRoleInstructionDao.replaceInstructionsForRole(TestDefaults.agentRole1.id, listOf(7L, 5L))
        }
        // A role write only selects rows: it never authors or rewrites their content.
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }

    @Test
    fun `updateRole links the requested rows in request order without rewriting their content`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))

        val result = service.updateRole(userId, roleId, validUpdateRequest(linkSlots(5L)))

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(roleId, listOf(5L)) }
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }

    @Test
    fun `updateRole drops removed entries from the list but keeps their rows`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))

        val result = service.updateRole(userId, roleId, validUpdateRequest(linkSlots(5L)))

        assertTrue(result.isRight())
        // An unlinked row survives as a library entry: the rewrite replaces links, it deletes no rows.
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(roleId, listOf(5L)) }
    }

    @Test
    fun `updateRole rejects the same instruction id twice in one request`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))

        val result = service.updateRole(userId, roleId, validUpdateRequest(linkSlots(5L, 5L)))

        val error = assertIs<UpdateAgentRoleError.DuplicateInstructionLink>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    @Test
    fun `updateRole rejects a missing or foreign instruction id as not found`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.updateRole(userId, roleId, validUpdateRequest(linkSlots(5L)))

        val error = assertIs<UpdateAgentRoleError.InstructionNotFound>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    @Test
    fun `createRole echoes the linked rows with their id and linking role ids`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns
            TestDefaults.agentRole1.copy(id = 1L)
        coEvery { agentRoleOwnershipDao.setOwner(1L, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(1L)) } returns
            mapOf(1L to listOf(InstructionRef(instructionId = 5L, sequence = 0)))
        coEvery { instructionDao.getInstructionsByIds(listOf(5L)) } returns
            listOf(TestDefaults.instruction1.copy(id = 5L, type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Be concise"))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(1L))

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = linkSlots(5L)))

        val echoed = result.getOrNull()!!.instructions.single()
        assertEquals(5L, echoed.id)
        assertEquals(setOf(1L), echoed.linkedRoleIds)
    }

    @Test
    fun `getAgentRoleById maps stored rows into domain subtypes with their identity fields`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns emptySet()
        coEvery {
            agentRoleInstructionDao.getLinksForRoles(listOf(roleId))
        } returns mapOf(
            roleId to listOf(
                InstructionRef(instructionId = 10L, sequence = 0),
                InstructionRef(instructionId = 11L, sequence = 1),
                InstructionRef(instructionId = 12L, sequence = 2)
            )
        )
        coEvery { instructionDao.getInstructionsByIds(listOf(10L, 11L, 12L)) } returns listOf(
            TestDefaults.instruction1.copy(id = 10L, message = "You are the architect."),
            TestDefaults.instruction1.copy(
                id = 11L,
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Swift mode",
                message = "Write idiomatic Swift",
                custom = """{"modelId":2}"""
            ),
            // Unknown kinds are dropped with a warning instead of failing the role read.
            TestDefaults.instruction1.copy(id = 12L, type = "mystery_kind", name = "Mystery", message = "x")
        )
        coEvery {
            agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(10L, 11L, 12L))
        } returns mapOf(10L to setOf(1L, 2L), 11L to setOf(1L), 12L to setOf(1L))

        val role = service.getAgentRoleById(userId, roleId).getOrNull()!!

        // Stored order comes from the link sequence; the unknown kind is dropped.
        assertEquals(listOf(10L, 11L), role.instructions.map { it.id })
        val roleInstruction = assertIs<RoleInstruction>(role.instructions[0])
        assertEquals("You are the architect.", roleInstruction.message)
        assertEquals(setOf(1L, 2L), roleInstruction.linkedRoleIds)
        val modelSpecific = assertIs<ModelSpecificInstruction>(role.instructions[1])
        assertEquals(2L, modelSpecific.modelId)
        assertEquals(setOf(1L), modelSpecific.linkedRoleIds)
    }

    @Test
    fun `getRoleById reports generated spawnable messages without storing them`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns
            TestDefaults.agentRole1.copy(id = roleId, projectId = null).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        // The generated text renders only when the role carries the `spawn_agent` tool.
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns setOf(21L)
        coEvery { toolDefinitionDao.getToolDefinitionsByIds(setOf(21L)) } returns listOf(
            OperatorToolDefinition(
                id = 21L,
                name = OperatorToolCatalog.SPAWN_AGENT_NAME,
                description = "Spawns a sub-agent",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { },
                outputSchema = null,
                isEnabled = true,
                createdAt = Instant.fromEpochMilliseconds(0L),
                updatedAt = Instant.fromEpochMilliseconds(0L),
                userId = userId
            )
        )
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns emptySet()
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, emptyList()) } returns emptyList()
        coEvery {
            agentRoleInstructionDao.getLinksForRoles(listOf(roleId))
        } returns mapOf(roleId to listOf(InstructionRef(instructionId = 10L, sequence = 0)))
        coEvery { instructionDao.getInstructionsByIds(listOf(10L)) } returns listOf(
            TestDefaults.instruction1.copy(
                id = 10L,
                type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                name = "Available agents",
                message = null
            )
        )
        coEvery {
            agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(10L))
        } returns mapOf(10L to setOf(roleId))

        val dto = service.getRoleById(userId, roleId).getOrNull()!!

        val entry = dto.instructions.single()
        assertEquals(AgentInstructionTypes.SPAWNABLE_AGENTS, entry.type)
        // The wire message is the generated marker text, never the (absent) stored message.
        assertTrue(entry.message.isNotBlank(), entry.message)
        assertEquals(10L, entry.id)
    }

    @Test
    fun `getRoleById maps custom rows into CustomInstruction in stored order`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns emptySet()
        coEvery {
            agentRoleInstructionDao.getLinksForRoles(listOf(roleId))
        } returns mapOf(
            roleId to listOf(
                InstructionRef(instructionId = 11L, sequence = 0),
                InstructionRef(instructionId = 10L, sequence = 1)
            )
        )
        coEvery { instructionDao.getInstructionsByIds(listOf(11L, 10L)) } returns listOf(
            TestDefaults.instruction1.copy(id = 11L, type = AgentInstructionTypes.CUSTOM, name = "First", message = "One"),
            TestDefaults.instruction1.copy(id = 10L, type = AgentInstructionTypes.CUSTOM, name = "Second", message = "Two")
        )
        coEvery {
            agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(11L, 10L))
        } returns mapOf(10L to setOf(roleId), 11L to setOf(roleId))

        val role = service.getAgentRoleById(userId, roleId).getOrNull()!!

        assertEquals(listOf("First", "Second"), role.instructions.map { it.name })
        assertTrue(role.instructions.all { it is CustomInstruction })
    }

    @Test
    fun `createRole returns an empty instruction list for a request without instructions`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(instructionSpecs = emptyList()))

        assertTrue(result.getOrNull()!!.instructions.isEmpty())
        // Full-replacement semantics: the (empty) link list is still written so prior links would clear.
        coVerify(exactly = 1) {
            agentRoleInstructionDao.replaceInstructionsForRole(TestDefaults.agentRole1.id, emptyList())
        }
    }

    @Test
    fun `getRoleById reports a model_specific target read from the stored custom text`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns emptySet()
        coEvery {
            agentRoleInstructionDao.getLinksForRoles(listOf(roleId))
        } returns mapOf(roleId to listOf(InstructionRef(instructionId = 10L, sequence = 0)))
        coEvery { instructionDao.getInstructionsByIds(listOf(10L)) } returns listOf(
            TestDefaults.instruction1.copy(
                id = 10L,
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Swift mode",
                message = "Write idiomatic Swift",
                custom = buildJsonObject { put("modelId", 3L) }.toString()
            )
        )
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(10L)) } returns
            mapOf(10L to setOf(roleId))

        val entry = service.getRoleById(userId, roleId).getOrNull()!!.instructions.single()

        assertEquals(3L, entry.modelSpecificId())
        assertEquals("Write idiomatic Swift", entry.message)
    }

    /**
     * Verifies that a create spec inserts one row, links it, and transfers ownership to the requester.
     */
    @Test
    fun `createRole materializes a create spec as a new owned row and links it`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        val content = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(content)))
        )

        assertTrue(result.isRight())
        // The row is inserted with the instruction endpoints' stored form and owned by the requester.
        coVerify(exactly = 1) {
            instructionDao.insertInstruction(
                type = AgentInstructionTypes.CUSTOM,
                name = "Tone",
                message = "Be concise",
                custom = null
            )
        }
        coVerify(exactly = 1) { instructionOwnershipDao.setOwner(any(), userId) }
        // The generated id is what the role links.
        coVerify(exactly = 1) {
            agentRoleInstructionDao.replaceInstructionsForRole(TestDefaults.agentRole1.id, listOf(100L))
        }
    }

    /**
     * Verifies that an update spec rewrites its target's content in place and links the same row.
     */
    @Test
    fun `createRole materializes an update spec by rewriting the loaded row in place`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        val content = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Rewritten"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Update(content)))
        )

        assertTrue(result.isRight())
        // The rewrite keeps the row's id, so every role linking the row observes the new content.
        coVerify(exactly = 1) {
            instructionDao.updateInstruction(
                match<InstructionEntity> { entity ->
                    entity.id == 5L && entity.name == "Tone" && entity.message == "Rewritten"
                }
            )
        }
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(TestDefaults.agentRole1.id, listOf(5L)) }
    }

    /**
     * Verifies that a save holding one create and one link spec creates exactly one row and never
     * rewrites the referenced one.
     */
    @Test
    fun `a create spec and a link spec create exactly one row and leave the linked row untouched`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        val content = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(content), InstructionSlot.Link(5L)))
        )

        assertTrue(result.isRight())
        coVerify(exactly = 1) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        // The referenced row is never rewritten: a save cannot revert a concurrent content edit.
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
        coVerify(exactly = 1) {
            agentRoleInstructionDao.replaceInstructionsForRole(TestDefaults.agentRole1.id, listOf(100L, 5L))
        }
    }

    /**
     * Verifies that inline content is accepted and rejected with the instruction endpoints' wording.
     */
    @Test
    fun `inline content the instruction endpoints would reject fails with the same wording`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        val invalid = CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "  ", message = "x")

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(invalid)))
        )

        val error = assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        assertEquals("Instruction name cannot be blank", error.reason)
    }

    /**
     * Verifies that the per-role singleton-kind rules judge inline specs as part of the effective list.
     */
    @Test
    fun `two inline specs of the same singleton kind are rejected by the per-role rules`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        val roleA = CreateInstructionRequest(type = AgentInstructionTypes.ROLE, name = "A", message = "One")
        val roleB = CreateInstructionRequest(type = AgentInstructionTypes.ROLE, name = "B", message = "Two")

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(roleA), InstructionSlot.Create(roleB)))
        )

        val error = assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        assertTrue(error.reason.contains("At most one 'role'"), error.reason)
    }

    /**
     * Verifies that a later invalid spec stops the save before any row, ownership, link or role write.
     */
    @Test
    fun `validation of every spec precedes any row or role write`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        val valid = CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Be concise")
        val invalid = CreateInstructionRequest(type = "mystery_kind", name = "Bad", message = "x")

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(valid), InstructionSlot.Create(invalid)))
        )

        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        // The later invalid slot is caught before the earlier valid slot is materialized, so no write
        // of any kind has happened yet.
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { instructionOwnershipDao.setOwner(any(), any()) }
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any()) }
    }

    /**
     * Verifies that one row referenced by both a link and an update spec is rejected as a duplicate.
     */
    @Test
    fun `duplicate targets across link and update specs are rejected`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Rewritten"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Link(5L), InstructionSlot.Update(update)))
        )

        val error = assertIs<CreateAgentRoleError.DuplicateInstructionLink>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
    }

    /**
     * Verifies that an update target deleted between validation and write collapses to not-found.
     */
    @Test
    fun `a vanished update target maps to instruction not found`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        // The row existed during validation but was deleted before the rewrite landed.
        coEvery { instructionDao.updateInstruction(any()) } returns InstructionError.NotFound(5L).left()
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Rewritten"
        )

        val result = service.updateRole(userId, roleId, validUpdateRequest(listOf(InstructionSlot.Update(update))))

        val error = assertIs<UpdateAgentRoleError.InstructionNotFound>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    /**
     * Verifies that a failed ownership link for an inline-created row fails the save before any link.
     */
    @Test
    fun `createRole surfaces an inline row ownership failure before any link is written`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { instructionOwnershipDao.setOwner(any(), any()) } returns
            SetOwnerError.ForeignKeyViolation("100", userId).left()
        val content = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Create(content)))
        )

        assertIs<CreateAgentRoleError.InstructionOwnerInsertFailed>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    /**
     * Verifies that the update flow reports an inline-created row's ownership failure the same way.
     */
    @Test
    fun `updateRole surfaces an inline row ownership failure`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { instructionOwnershipDao.setOwner(any(), any()) } returns
            SetOwnerError.ForeignKeyViolation("100", userId).left()
        val content = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )

        val result = service.updateRole(
            userId,
            roleId,
            validUpdateRequest(listOf(InstructionSlot.Create(content)))
        )

        assertIs<UpdateAgentRoleError.InstructionOwnerInsertFailed>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    /**
     * Verifies that a role save refuses an inline change that would break another linking role's list.
     */
    @Test
    fun `updateRole refuses an update spec that would invalidate another linking role's list`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        // The written role links the row, but so does role 2, which already holds a 'main' row: retyping
        // row 5 to 'main' would leave role 2 with two.
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(roleId, 2L))
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(2L)) } returns
            mapOf(2L to listOf(InstructionRef(5L, 0), InstructionRef(7L, 1)))
        coEvery { instructionDao.getInstructionsByIds(listOf(7L)) } returns
            listOf(TestDefaults.instruction1.copy(id = 7L, type = AgentInstructionTypes.MAIN, message = "Text"))
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.MAIN,
            name = "Main",
            message = "Text"
        )

        val result = service.updateRole(userId, roleId, validUpdateRequest(listOf(InstructionSlot.Update(update))))

        val error = assertIs<UpdateAgentRoleError.LinkedRoleInstructionListInvalid>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        assertEquals(listOf(2L), error.linkedRoleIds)
        assertEquals("At most one 'main' instruction is allowed", error.reason)
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
    }

    /**
     * Verifies that a role save may retype a row when no other role links it.
     */
    @Test
    fun `updateRole allows an update spec kind change when only the written role links the row`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(roleId))
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.MAIN,
            name = "Main",
            message = "Text"
        )

        val result = service.updateRole(userId, roleId, validUpdateRequest(listOf(InstructionSlot.Update(update))))

        assertTrue(result.isRight())
        // The written role's own list is validated by the request validator, so its own link never blocks
        // a change the resulting list already allows.
        coVerify(exactly = 1) { instructionDao.updateInstruction(match { it.type == AgentInstructionTypes.MAIN }) }
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(roleId, listOf(5L)) }
    }

    /**
     * Verifies that a role save accepts a change that leaves another linking role's list valid.
     */
    @Test
    fun `updateRole allows an update spec kind change that keeps another linking role's list valid`() = runTest {
        val roleId = 1L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        // Role 2 links only row 5, so its resulting list is a single 'main' row.
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(roleId, 2L))
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(2L)) } returns
            mapOf(2L to listOf(InstructionRef(5L, 0)))
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.MAIN,
            name = "Main",
            message = "Text"
        )

        val result = service.updateRole(userId, roleId, validUpdateRequest(listOf(InstructionSlot.Update(update))))

        assertTrue(result.isRight())
        coVerify(exactly = 1) { instructionDao.updateInstruction(match { it.type == AgentInstructionTypes.MAIN }) }
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(roleId, listOf(5L)) }
    }

    /**
     * Verifies that a new role may not make another linking role's list invalid.
     */
    @Test
    fun `createRole refuses an update spec that would invalidate another linking role's list`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any()) } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        stubOwnedInstructions(TestInstructionRow(5L, AgentInstructionTypes.CUSTOM))
        // A brand-new role links nothing yet, so role 2 is the only linking role, and its 'main' row
        // makes the retype illegal.
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(2L))
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(2L)) } returns
            mapOf(2L to listOf(InstructionRef(5L, 0), InstructionRef(7L, 1)))
        coEvery { instructionDao.getInstructionsByIds(listOf(7L)) } returns
            listOf(TestDefaults.instruction1.copy(id = 7L, type = AgentInstructionTypes.MAIN, message = "Text"))
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.MAIN,
            name = "Main",
            message = "Text"
        )

        val result = service.createRole(
            userId,
            validRequest().copy(instructionSpecs = listOf(InstructionSlot.Update(update)))
        )

        val error = assertIs<CreateAgentRoleError.LinkedRoleInstructionListInvalid>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        assertEquals(listOf(2L), error.linkedRoleIds)
        assertEquals("At most one 'main' instruction is allowed", error.reason)
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }
}
