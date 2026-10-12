package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pins the ordering contract the session list panel renders: each section is ordered by `updatedAt` descending, and
 * sessions sharing an instant keep the relative order they were given in.
 */
class SessionListDataOrderingTest {

    private val base = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val group = ChatGroup(id = 5L, name = "Group", createdAt = base)

    /** An instant [offsetMillis] after the fixed base instant of the case. */
    private fun at(offsetMillis: Long) = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + offsetMillis)

    /** Builds the summary of a session of [groupId]. */
    private fun summary(id: Long, updatedAt: Instant, groupId: Long? = null) = ChatSessionSummary(
        id = id,
        name = "Session $id",
        createdAt = base,
        updatedAt = updatedAt,
        groupId = groupId
    )

    /** The rendered order of the section [section]; `null` is the ungrouped section. */
    private fun SessionListData.orderOf(section: ChatGroup?): List<Long> =
        groupedSessions.getValue(section).map { it.id }

    @Test
    fun `a group section is ordered by updatedAt descending`() {
        val data = SessionListData(
            allSessions = listOf(
                summary(1L, at(1_000), groupId = group.id),
                summary(2L, at(3_000), groupId = group.id),
                summary(3L, at(2_000), groupId = group.id)
            ),
            allGroups = listOf(group)
        )

        assertEquals(listOf(2L, 3L, 1L), data.orderOf(group))
    }

    @Test
    fun `the ungrouped section is ordered by updatedAt descending`() {
        val data = SessionListData(
            allSessions = listOf(
                summary(1L, at(2_000)),
                summary(2L, at(3_000)),
                summary(3L, at(1_000))
            ),
            allGroups = emptyList()
        )

        assertEquals(listOf(2L, 1L, 3L), data.orderOf(null))
    }

    @Test
    fun `sessions sharing an instant keep their previous relative order`() {
        val data = SessionListData(
            allSessions = listOf(
                summary(2L, at(3_000)),
                summary(9L, at(3_000)),
                summary(4L, at(1_000)),
                summary(7L, at(3_000))
            ),
            allGroups = emptyList()
        )

        // A stable sort, so the three equal instants keep the order they arrived in instead of being reshuffled.
        assertEquals(listOf(2L, 9L, 7L, 4L), data.orderOf(null))
    }
}
