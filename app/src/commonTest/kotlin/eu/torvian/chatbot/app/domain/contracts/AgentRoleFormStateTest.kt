package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
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
        assertEquals("Model instruction", defaultInstructionName(AgentInstructionTypes.MODEL_SETTINGS))
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
            AgentInstructionTypes.MODEL_SETTINGS,
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
        assertEquals(1, request.instructions.size)
        assertEquals(AgentInstructionTypes.ROLE, request.instructions[0].type)
    }

    @Test
    fun `toCreateRequest throws when model is missing`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelId = null)
        assertFailsWith<IllegalStateException> { form.toCreateRequest() }
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
