package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the agent-role form draft helpers: conventional default instruction labels, form
 * validation (name only — a preset-less role is legal, U-36), preset-based request mapping and error
 * propagation.
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
    fun `empty form starts without a model preset`() {
        assertNull(createEmptyAgentRoleForm().modelPresetId)
    }

    @Test
    fun `validate rejects blank name`() {
        assertEquals("Role name cannot be empty.", createEmptyAgentRoleForm().copy(name = "  ").validate())
    }

    @Test
    fun `validate accepts a preset-less draft`() {
        // U-36/RQ-2: the model preset is optional on the client too, so a name-only role is savable
        // and the settings UI merely flags it as non-sendable.
        assertNull(createEmptyAgentRoleForm().copy(name = "Test").validate())
    }

    @Test
    fun `validate passes when a preset is attached`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelPresetId = 3L)
        assertNull(form.validate())
    }

    @Test
    fun `toCreateRequest maps all fields including instructions and the preset`() {
        val form = AgentRoleFormState(
            mode = FormMode.NEW,
            name = "My Role",
            displayName = "Display",
            description = "A description",
            modelPresetId = 3L,
            toolIds = setOf(10L, 20L),
            spawnableAgentRoleIds = setOf(30L),
            projectId = 100L,
            instructions = listOf(
                AgentRoleInstructionDraft(
                    type = AgentInstructionTypes.ROLE,
                    name = "Role",
                    message = "You are awesome"
                )
            )
        )
        val request = form.toCreateRequest()
        assertEquals("My Role", request.name)
        assertEquals("Display", request.displayName)
        assertEquals("A description", request.description)
        assertEquals(3L, request.modelPresetId)
        assertEquals(setOf(10L, 20L), request.toolIds)
        assertEquals(setOf(30L), request.spawnableAgentRoleIds)
        assertEquals(100L, request.projectId)
        // The new draft rides along as an inline create spec.
        assertEquals(
            listOf(
                InstructionSlot.Create(
                    CreateInstructionRequest(
                        type = AgentInstructionTypes.ROLE,
                        name = "Role",
                        message = "You are awesome"
                    )
                )
            ),
            request.instructionSpecs
        )
    }

    @Test
    fun `toCreateRequest maps a preset-less draft to a null preset id`() {
        // The removed modelId/modelSettingsId inputs must never come back: a preset-less draft maps
        // to a null preset id and nothing else configuration-related (U-28/U-29/U-36).
        val request = createEmptyAgentRoleForm().copy(name = "Test").toCreateRequest()
        assertEquals("Test", request.name)
        assertNull(request.modelPresetId)
    }

    @Test
    fun `toCreateRequest maps projectId`() {
        val form = createEmptyAgentRoleForm().copy(
            name = "Test",
            modelPresetId = 3L,
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
            modelPresetId = 3L,
            projectId = 5L
        )
        assertEquals(5L, edit.toUpdateRequest().projectId)
        assertEquals(3L, edit.toUpdateRequest().modelPresetId)
        assertEquals(null, createEmptyAgentRoleForm().projectId)
    }

    @Test
    fun `toSlot maps new edited and untouched drafts to create update and link slots`() {
        val stored = AgentInstructionDto(
            id = 10L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Style",
            message = "Be concise."
        )
        val untouched = stored.toDraft()
        val edited = untouched.copy(message = "Be friendly.")
        val added = AgentRoleInstructionDraft(
            type = AgentInstructionTypes.ROLE,
            name = "Role",
            message = "You are awesome"
        )

        assertEquals(
            InstructionSlot.Create(
                CreateInstructionRequest(
                    type = AgentInstructionTypes.ROLE,
                    name = "Role",
                    message = "You are awesome"
                )
            ),
            added.toSlot()
        )
        assertEquals(
            InstructionSlot.Update(
                UpdateInstructionRequest(
                    id = 10L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Style",
                    message = "Be friendly."
                )
            ),
            edited.toSlot()
        )
        assertEquals(InstructionSlot.Link(10L), untouched.toSlot())
    }

    @Test
    fun `the request carries the drafts as the role's full ordered link set`() {
        val form = AgentRoleFormState(
            mode = FormMode.EDIT,
            roleId = 7L,
            name = "Test",
            instructions = listOf(
                AgentRoleInstructionDraft(
                    type = AgentInstructionTypes.ROLE,
                    name = "Role",
                    message = "You are awesome"
                ),
                AgentInstructionDto(
                    id = 10L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Style",
                    message = "Be concise."
                ).toDraft()
            )
        )

        // Draft order is the role's order; the whole list is one atomic save's input.
        assertEquals(form.instructions.map { it.toSlot() }, form.toUpdateRequest().instructionSpecs)
        assertEquals(form.instructions.map { it.toSlot() }, form.toCreateRequest().instructionSpecs)
    }

    @Test
    fun `toEditFormState keeps reported instructions as drafts that already have rows`() {
        val role = AgentRoleDto(
            id = 9L,
            name = "writer",
            displayName = "Writer",
            description = "Writes",
            modelId = null,
            modelSettingsId = null,
            modelPresetId = null,
            tools = emptySet(),
            instructions = listOf(
                AgentInstructionDto(
                    id = 10L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Style",
                    message = "Be concise.",
                    linkedRoleIds = setOf(1L, 2L)
                )
            )
        )

        val draft = role.toEditFormState().instructions.single()

        assertEquals(10L, draft.id)
        assertEquals(setOf(1L, 2L), draft.linkedRoleIds)
        // Unchanged content is not rewritten when the role is saved again.
        assertEquals(false, draft.needsWrite)
    }

    @Test
    fun `a new draft needs a write and an untouched stored draft does not`() {
        val newDraft = AgentRoleInstructionDraft(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise."
        )
        val stored = AgentInstructionDto(
            id = 42L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise."
        ).toDraft()

        assertEquals(true, newDraft.needsWrite)
        assertEquals(false, stored.needsWrite)
        // An edit after the write is detected again, so the row is rewritten on the next save.
        assertEquals(true, stored.copy(message = "Be friendly.").needsWrite)
    }

    @Test
    fun `toUpdateRequest names the stored row and is absent for an unwritten draft`() {
        val newDraft = AgentRoleInstructionDraft(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise."
        )
        val storedDraft = AgentRoleInstructionDraft(
            id = 42L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise."
        )

        // Nothing to update before the row exists: the draft has to be created first.
        assertNull(newDraft.toUpdateRequest())
        val request = storedDraft.toUpdateRequest()
        assertEquals(42L, request?.id)
        assertEquals("Be concise.", request?.message)
    }

    @Test
    fun `a spawnable_agents draft ignores its generated message when detecting changes`() {
        // The server regenerates the marker text per role, so a redisplayed draft must not look edited.
        val draft = AgentRoleInstructionDraft(
            id = 10L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents",
            message = "generated text",
            original = AgentInstructionDto(
                id = 10L,
                type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                name = "Available agents",
                message = ""
            )
        )

        assertEquals(false, draft.needsWrite)
    }

    @Test
    fun `toEditFormState carries the preset reference instead of the derived ids`() {
        // The DTO's modelId/modelSettingsId are server-resolved read-only values; only the preset is
        // a legal role input, so the edit draft must not pick them up.
        val role = AgentRoleDto(
            id = 9L,
            name = "writer",
            displayName = "Writer",
            description = "Writes",
            modelId = 1L,
            modelSettingsId = 2L,
            modelPresetId = 3L,
            tools = setOf(4L),
            instructions = emptyList()
        )

        val form = role.toEditFormState()

        assertEquals(FormMode.EDIT, form.mode)
        assertEquals(9L, form.roleId)
        assertEquals("writer", form.name)
        assertEquals("Writer", form.displayName)
        assertEquals(3L, form.modelPresetId)
        assertEquals(setOf(4L), form.toolIds)
    }

    @Test
    fun `toEditFormState keeps a preset-less role preset-less`() {
        val role = AgentRoleDto(
            id = 9L,
            name = "writer",
            displayName = null,
            description = "",
            modelId = null,
            modelSettingsId = null,
            modelPresetId = null,
            tools = emptySet(),
            instructions = emptyList()
        )

        assertNull(role.toEditFormState().modelPresetId)
    }

    @Test
    fun `withProjectScope changes the project and keeps every selected spawn target`() {
        // The role's project never constrains its spawn allow-list, so a project switch only moves the
        // role: self, a colleague from the old project, a role of the new project and an id that is not
        // in the role stream any more all stay selected instead of being dropped silently.
        val form = createEmptyAgentRoleForm().copy(
            mode = FormMode.EDIT,
            roleId = 5L,
            name = "Test",
            modelPresetId = 3L,
            projectId = 50L,
            spawnableAgentRoleIds = setOf(5L, 6L, 9L, 99L)
        )

        val updated = form.withProjectScope(60L)

        assertEquals(60L, updated.projectId)
        assertEquals(setOf(5L, 6L, 9L, 99L), updated.spawnableAgentRoleIds)
    }

    @Test
    fun `withProjectScope to no project keeps every selected spawn target`() {
        // Moving the role to the unassociated scope likewise keeps the selection: project-bound targets
        // remain legal spawn targets for an unassociated role.
        val form = createEmptyAgentRoleForm().copy(
            mode = FormMode.EDIT,
            roleId = 5L,
            name = "Test",
            modelPresetId = 3L,
            projectId = 50L,
            spawnableAgentRoleIds = setOf(5L, 6L, 7L)
        )

        val updated = form.withProjectScope(null)

        assertEquals(null, updated.projectId)
        assertEquals(setOf(5L, 6L, 7L), updated.spawnableAgentRoleIds)
    }

    @Test
    fun `withError sets the error message without touching other fields`() {
        val form = createEmptyAgentRoleForm().copy(name = "Test", modelPresetId = 3L)
        val withError = form.withError("Something went wrong")
        assertEquals("Something went wrong", withError.errorMessage)
        assertEquals("Test", withError.name)
        assertEquals(3L, withError.modelPresetId)
        assertEquals(null, form.errorMessage)
    }
}
