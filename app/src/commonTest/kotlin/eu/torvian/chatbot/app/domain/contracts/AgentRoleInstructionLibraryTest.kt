package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.shared
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the existing-instruction picker's pure helpers: which library rows a role may link, and how
 * a library row becomes a form draft.
 *
 * The rules mirror the server's per-role validation, so a pick can never build a draft the write path
 * would reject, and a picked row keeps its identity so saving the role links it instead of copying it.
 */
class AgentRoleInstructionLibraryTest {

    private fun libraryRow(
        id: Long,
        type: String = AgentInstructionTypes.CUSTOM,
        name: String = "Row $id",
        message: String = "Text",
        modelId: Long? = null
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = modelId?.let { target -> buildJsonObject { put("modelId", target) } }
    )

    private fun draft(
        id: Long? = null,
        type: String = AgentInstructionTypes.CUSTOM,
        modelId: Long? = null
    ) = AgentRoleInstructionDraft(
        id = id,
        type = type,
        name = "Draft",
        message = "",
        custom = modelId?.let { target -> buildJsonObject { put("modelId", target) } }
    )

    /** A library covering every kind the picker has to reason about, including the unusable ones. */
    private val library = listOf(
        libraryRow(id = 1L, name = "Tone"),
        libraryRow(id = 2L, type = AgentInstructionTypes.ROLE),
        libraryRow(id = 3L, type = AgentInstructionTypes.MAIN),
        libraryRow(id = 4L, type = AgentInstructionTypes.SPAWNABLE_AGENTS, message = ""),
        libraryRow(id = 5L, type = AgentInstructionTypes.MODEL_SPECIFIC, modelId = 5L),
        libraryRow(id = 6L, type = AgentInstructionTypes.MODEL_SPECIFIC, modelId = 6L),
        // Unusable targets and unknown kinds are never offered, mirroring the read mapper and the
        // authoring validation of the server.
        libraryRow(id = 7L, type = AgentInstructionTypes.MODEL_SPECIFIC, modelId = null),
        libraryRow(id = 8L, type = "skills")
    )

    @Test
    fun `editableInstructionTypes lists every client-editable kind`() {
        assertEquals(
            listOf(
                AgentInstructionTypes.ROLE,
                AgentInstructionTypes.MAIN,
                AgentInstructionTypes.CUSTOM,
                AgentInstructionTypes.SPAWNABLE_AGENTS,
                AgentInstructionTypes.MODEL_SPECIFIC
            ),
            editableInstructionTypes
        )
    }

    @Test
    fun `an empty role may link every usable library row`() {
        val selectable = selectableLibraryInstructions(library = library, instructions = emptyList())

        // Multi-instance kinds and both usable model targets stay available; the unknown kind and the
        // model_specific row without a target are not offered at all.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), selectable.map { it.id })
    }

    @Test
    fun `a row the role already links is not offered again`() {
        val selectable = selectableLibraryInstructions(library = library, instructions = listOf(draft(id = 1L)))

        assertTrue(selectable.none { it.id == 1L })
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), selectable.map { it.id })
    }

    @Test
    fun `single-instance kinds already present are not offered`() {
        val selectable = selectableLibraryInstructions(
            library = library,
            instructions = listOf(
                draft(type = AgentInstructionTypes.ROLE),
                draft(type = AgentInstructionTypes.MAIN),
                draft(type = AgentInstructionTypes.SPAWNABLE_AGENTS)
            )
        )

        // A second role/main/spawnable row would be rejected by the server, so none of them is offered.
        assertEquals(listOf(1L, 5L, 6L), selectable.map { it.id })
    }

    @Test
    fun `a model_specific row whose target is taken is not offered`() {
        val selectable = selectableLibraryInstructions(
            library = library,
            instructions = listOf(draft(type = AgentInstructionTypes.MODEL_SPECIFIC, modelId = 5L))
        )

        // Each model_specific row must target its own model, so only the unused target stays available.
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), selectable.map { it.id })
    }

    @Test
    fun `a picked row becomes a draft that keeps its identity and needs no write`() {
        val picked = libraryRow(id = 10L, name = "Code style", message = "Prefer Kotlin idioms").toDraft()

        assertEquals(10L, picked.id)
        assertEquals(AgentInstructionTypes.CUSTOM, picked.type)
        assertEquals("Code style", picked.name)
        assertEquals("Prefer Kotlin idioms", picked.message)
        // The row already holds this content, so saving an untouched role must leave it alone.
        assertEquals(false, picked.needsWrite)
    }

    @Test
    fun `a picked generated-message row becomes a draft with an empty message`() {
        val picked = libraryRow(
            id = 11L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents",
            message = ""
        ).toDraft()

        // A library row of that kind has no role context to generate text from, so it reports an empty
        // message; its draft behaves like a freshly added one and does not look edited either (the
        // stored form compares equal).
        assertEquals("", picked.message)
        assertEquals(false, picked.needsWrite)
    }

    @Test
    fun `a picked row keeps the linking roles the library reported`() {
        val picked = libraryRow(id = 12L, name = "Tone")
            .copy(linkedRoleIds = setOf(2L, 7L))
            .toDraft()

        // The picker can tell the user that the row is shared before the role is saved.
        assertEquals(setOf(2L, 7L), picked.linkedRoleIds)
        assertEquals(true, picked.original?.shared)
        assertEquals(false, picked.needsWrite)
    }
}
