package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the agent-role form draft helpers: conventional default instruction labels, form
 * validation, request mapping and error propagation.
 */
class AgentRoleFormStateTest {

    @Test
    fun `defaultInstructionName maps every well-known type to its conventional label`() {
        assertEquals("Role", defaultInstructionName(AgentInstructionTypes.ROLE))
        assertEquals("Main instruction", defaultInstructionName(AgentInstructionTypes.MAIN))
        assertEquals("Available agents", defaultInstructionName(AgentInstructionTypes.SPAWNABLE_AGENTS))
        assertEquals("Custom instruction", defaultInstructionName(AgentInstructionTypes.CUSTOM))
        assertEquals("Model-specific instruction", defaultInstructionName(AgentInstructionTypes.MODEL_SPECIFIC))
    }

    @Test
    fun `defaultInstructionName falls back to the raw type key for unknown types`() {
        assertEquals("skills", defaultInstructionName("skills"))
    }

    @Test
    fun `empty form presets one instruction per well-known type in canonical order`() {
        val form = createEmptyAgentRoleForm()

        val expectedTypes = listOf(
            AgentInstructionTypes.ROLE,
            AgentInstructionTypes.MAIN,
            AgentInstructionTypes.SPAWNABLE_AGENTS,
            AgentInstructionTypes.CUSTOM
        )
        assertEquals(expectedTypes, form.instructions.map { it.type })
        assertEquals(
            expectedTypes.map(::defaultInstructionName),
            form.instructions.map { it.name }
        )
    }

    @Test
    fun `validate rejects blank name`() {
        assertEquals("Role name cannot be empty.", createEmptyAgentRoleForm().copy(name = "  ").validate())
    }

    @Test
    fun `validate rejects missing model`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = null)
        assertEquals("A model must be selected.", form.validate())
    }

    @Test
    fun `validate rejects missing settings`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = 1L, modelSettingsId = null)
        assertEquals("A settings profile must be selected for the model.", form.validate())
    }

    @Test
    fun `validate passes when all required fields are present`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = 1L, modelSettingsId = 2L)
        assertEquals(null, form.validate())
    }

    @Test
    fun `toCreateRequest maps all fields including instructions`() {
        val form = AgentRoleFormState(
            mode = FormMode.NEW,
            name = "My Role",
            displayName = "Display",
            description = "A description",
            modelId = 1L,
            modelSettingsId = 2L,
            toolIds = setOf(10L, 20L),
            spawnableAgentRoleIds = setOf(30L),
            projectId = 100L,
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are awesome")
            )
        )
        val request = form.toCreateRequest()
        assertEquals("My Role", request.name)
        assertEquals("Display", request.displayName)
        assertEquals("A description", request.description)
        assertEquals(1L, request.modelId)
        assertEquals(2L, request.modelSettingsId)
        assertEquals(setOf(10L, 20L), request.toolIds)
        assertEquals(setOf(30L), request.spawnableAgentRoleIds)
        assertEquals(100L, request.projectId)
        assertEquals(1, request.instructions.size)
        assertEquals(AgentInstructionTypes.ROLE, request.instructions[0].type)
    }

    @Test
    fun `toCreateRequest maps projectId`() {
        val form = createEmptyAgentRoleForm().copy(
            name = "Test",
            modelId = 1L,
            modelSettingsId = 2L,
            projectId = 5L
        )
        assertEquals(5L, form.toCreateRequest().projectId)
    }

    @Test
    fun `toUpdateRequest maps projectId and empty form keeps it unassociated`() {
        val edit = AgentRoleFormState(
            mode = FormMode.EDIT,
            roleId = 7L,
            name = "Test",
            modelId = 1L,
            modelSettingsId = 2L,
            projectId = 5L
        )
        assertEquals(5L, edit.toUpdateRequest().projectId)
        assertEquals(null, createEmptyAgentRoleForm().projectId)
    }

    @Test
    fun `toCreateRequest throws when model is missing`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = null)
        assertFailsWith<IllegalStateException> { form.toCreateRequest() }
    }

    @Test
    fun `withProjectScope keeps only spawn targets sharing the new project scope`() {
        // The draft was on project 50 with the edited role (self) and a colleague selected; the user
        // switches the role to project 60. The colleague belongs to 50 only, so it must be dropped;
        // self-spawn stays eligible because the edited role is same-scope after the save.
        val editedRole = roleDto(id = 5L, projectId = 50L)
        val colleague = roleDto(id = 6L, projectId = 50L)
        val newProjectRole = roleDto(id = 9L, projectId = 60L)
        val form = createEmptyAgentRoleForm().copy(
            mode = FormMode.EDIT,
            roleId = 5L,
            name = "Test",
            modelId = 1L,
            modelSettingsId = 2L,
            projectId = 50L,
            spawnableAgentRoleIds = setOf(5L, 6L, 9L)
        )

        val updated = form.withProjectScope(60L, listOf(editedRole, colleague, newProjectRole))

        assertEquals(60L, updated.projectId)
        // 6 left the old scope; 9 joins the new scope; 5 (self) is always kept.
        assertEquals(setOf(5L, 9L), updated.spawnableAgentRoleIds)
    }

    @Test
    fun `withProjectScope to no project drops every project-bound spawn target`() {
        // Switching the role from a project to "No project" must drop targets that belonged to the
        // old project (they would otherwise fail the same-project check on save), keeping only
        // unassociated targets plus self.
        val editedRole = roleDto(id = 5L, projectId = 50L)
        val colleague = roleDto(id = 6L, projectId = 50L)
        val unassociated = roleDto(id = 7L, projectId = null)
        val form = createEmptyAgentRoleForm().copy(
            mode = FormMode.EDIT,
            roleId = 5L,
            name = "Test",
            modelId = 1L,
            modelSettingsId = 2L,
            projectId = 50L,
            spawnableAgentRoleIds = setOf(5L, 6L, 7L)
        )

        val updated = form.withProjectScope(null, listOf(editedRole, colleague, unassociated))

        assertEquals(null, updated.projectId)
        assertEquals(setOf(5L, 7L), updated.spawnableAgentRoleIds)
    }

    @Test
    fun `withProjectScope keeps a target of the new scope already selected`() {
        // Switching from "No project" to project 50 keeps a target that already belongs to 50 and
        // drops targets that no longer share the scope; an id absent from the role list is dropped
        // (stale/deleted target) instead of being saved.
        val inProject = roleDto(id = 6L, projectId = 50L)
        val form = createEmptyAgentRoleForm().copy(
            mode = FormMode.NEW,
            name = "Test",
            modelId = 1L,
            modelSettingsId = 2L,
            spawnableAgentRoleIds = setOf(6L, 99L)
        )

        val updated = form.withProjectScope(50L, listOf(inProject))

        assertEquals(50L, updated.projectId)
        assertEquals(setOf(6L), updated.spawnableAgentRoleIds)
    }

    @Test
    fun `withError sets the error message without touching other fields`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = 1L, modelSettingsId = 2L)
        val withError = form.withError("Something went wrong")
        assertEquals("Something went wrong", withError.errorMessage)
        assertEquals("Test", withError.name)
        assertEquals(1L, withError.modelId)
        assertEquals(2L, withError.modelSettingsId)
        assertEquals(null, form.errorMessage)
    }
}

/**
 * Builds a minimal same-user [AgentRoleDto] spawn target for the scope-pruning tests.
 *
 * @param id The role identifier.
 * @param projectId The role's single project membership, or null when unassociated.
 * @return A bare [AgentRoleDto] with the requested id and project scope.
 */
private fun roleDto(id: Long, projectId: Long?): AgentRoleDto = AgentRoleDto(
    id = id,
    name = "role-$id",
    displayName = null,
    description = "",
    modelId = 1L,
    modelSettingsId = 2L,
    tools = emptySet(),
    instructions = emptyList(),
    projectId = projectId
)
