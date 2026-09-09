package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the project form draft helpers: empty-draft defaults, edit pre-fill, validation and
 * request mapping (including the `agentRoleIds` membership carried on both request directions).
 */
class ProjectFormStateTest {

    private fun project(
        id: Long,
        name: String,
        description: String = "",
        agentRoleIds: Set<Long> = emptySet()
    ): ProjectDto = ProjectDto(
        id = id,
        name = name,
        description = description,
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = agentRoleIds
    )

    @Test
    fun `empty form starts blank and new`() {
        val form = createEmptyProjectForm()
        assertEquals(FormMode.NEW, form.mode)
        assertEquals("", form.name)
        assertEquals("", form.description)
        assertEquals(emptySet(), form.agentRoleIds)
        assertNull(form.errorMessage)
    }

    @Test
    fun `validate rejects blank name`() {
        val form = createEmptyProjectForm().copy(name = "   ")
        assertEquals("Project name cannot be empty.", form.validate())
    }

    @Test
    fun `validate rejects overlong name`() {
        val form = createEmptyProjectForm().copy(name = "x".repeat(256))
        assertTrue(form.validate()!!.contains("cannot exceed"))
    }

    @Test
    fun `validate passes with name only`() {
        assertNull(createEmptyProjectForm().copy(name = "My Project").validate())
    }

    @Test
    fun `toCreateRequest maps all fields including agentRoleIds`() {
        val form = createEmptyProjectForm().copy(
            name = "  Research  ",
            description = "  Group of writing roles  ",
            agentRoleIds = setOf(1L, 2L)
        )
        val request = form.toCreateRequest()
        assertEquals("Research", request.name)
        assertEquals("Group of writing roles", request.description)
        assertEquals(setOf(1L, 2L), request.agentRoleIds)
    }

    @Test
    fun `toUpdateRequest maps all fields including agentRoleIds`() {
        val form = createEmptyProjectForm().copy(
            mode = FormMode.EDIT,
            projectId = 9L,
            name = "Research",
            agentRoleIds = setOf(3L)
        )
        val request = form.toUpdateRequest()
        assertEquals("Research", request.name)
        assertEquals(setOf(3L), request.agentRoleIds)
    }

    @Test
    fun `edit form pre-fills from project including member roles`() {
        val form = project(9L, "Research", "Writing group", setOf(4L, 5L)).toEditFormState()
        assertEquals(FormMode.EDIT, form.mode)
        assertEquals(9L, form.projectId)
        assertEquals("Research", form.name)
        assertEquals("Writing group", form.description)
        assertEquals(setOf(4L, 5L), form.agentRoleIds)
    }

    @Test
    fun `withError sets the error message without touching other fields`() {
        val form = createEmptyProjectForm().copy(name = "Research")
        val withError = form.withError("Something went wrong")
        assertEquals("Something went wrong", withError.errorMessage)
        assertEquals("Research", withError.name)
        assertNull(form.errorMessage)
    }
}