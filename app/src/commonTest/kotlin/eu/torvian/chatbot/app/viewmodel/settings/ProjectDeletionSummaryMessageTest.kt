package eu.torvian.chatbot.app.viewmodel.settings

import eu.torvian.chatbot.common.models.api.project.DeleteProjectResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for [formatProjectDeletionSummary]: the toast text reports counts only and omits every empty
 * impact clause, and a missing project name falls back to naming the id.
 */
class ProjectDeletionSummaryMessageTest {

    private fun result(
        projectId: Long = 7L,
        deletedAgentRoleIds: List<Long> = emptyList(),
        deletedInstructionIds: List<Long> = emptyList(),
        retainedInstructionIds: List<Long> = emptyList()
    ) = DeleteProjectResponse(projectId, deletedAgentRoleIds, deletedInstructionIds, retainedInstructionIds)

    @Test
    fun `reports the deleted role count`() {
        val message = formatProjectDeletionSummary("Research", result(deletedAgentRoleIds = listOf(10L, 11L)))

        assertEquals("Deleted project 'Research'. Deleted 2 agent role(s).", message)
    }

    @Test
    fun `reports the removed instruction count`() {
        val message = formatProjectDeletionSummary("Research", result(deletedInstructionIds = listOf(3L)))

        assertEquals(
            "Deleted project 'Research'. Removed 1 instruction(s) that lost their last link.",
            message
        )
    }

    @Test
    fun `reports the kept instruction count`() {
        val message = formatProjectDeletionSummary("Research", result(retainedInstructionIds = listOf(5L, 6L)))

        assertEquals("Deleted project 'Research'. Kept 2 instruction(s) still linked by other roles.", message)
    }

    @Test
    fun `reports all clauses in order and never names ids`() {
        val message = formatProjectDeletionSummary(
            "Research",
            result(
                deletedAgentRoleIds = listOf(10L, 11L),
                deletedInstructionIds = listOf(3L),
                retainedInstructionIds = listOf(5L)
            )
        )

        assertEquals(
            "Deleted project 'Research'. Deleted 2 agent role(s). " +
                "Removed 1 instruction(s) that lost their last link. " +
                "Kept 1 instruction(s) still linked by other roles.",
            message
        )
        // Counts only: the user-facing toast must not render the underlying ids.
        listOf(10L, 11L, 3L, 5L).forEach { id ->
            assertFalse(
                Regex("\\b$id\\b").containsMatchIn(message),
                "id $id must not appear in the toast"
            )
        }
    }

    @Test
    fun `falls back to naming the id when the project name is unknown`() {
        val message = formatProjectDeletionSummary(null, result(projectId = 42L))

        assertEquals("Deleted project (id: 42).", message)
    }
}
