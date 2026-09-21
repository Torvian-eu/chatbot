package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for the deterministic ordering of [AgentRoleMapper.loadSpawnableAgentsAdvertisement]: the
 * advertisement's target order must not depend on the row order the DAO happens to return.
 */
class AgentRoleMapperSpawnAdvertisementTest {

    /** Mocked role-row DAO backing the allow-list target read. */
    private val agentRoleDao = mockk<AgentRoleDao>()

    /** Mocked tool DAO; unused by the loader but required by the mapper's constructor. */
    private val toolDefinitionDao = mockk<ToolDefinitionDao>()

    /** Mocked project DAO backing the batched project labels. */
    private val projectDao = mockk<ProjectDao>()

    /** Mapper under test, wired to the mocked DAOs. */
    private val mapper = AgentRoleMapper(
        agentRoleDao = agentRoleDao,
        toolDefinitionDao = toolDefinitionDao,
        projectDao = projectDao,
        json = Json { ignoreUnknownKeys = true }
    )

    /** Owner the advertisement is resolved for. */
    private val userId = 7L

    /**
     * Verifies the sort key: targets are ordered by their lower-cased displayed label, with the
     * machine name supplying the label of a target whose display name is blank.
     */
    @Test
    fun `loadSpawnableAgentsAdvertisement orders targets by displayed label`() = runTest {
        // The DAO order is neither alphabetical nor case-consistent, and one target's display name is
        // blank, so the sort must use the lower-cased label with the name as the fallback.
        coEvery { agentRoleDao.getRolesByIdsForUser(any(), any()) } returns listOf(
            TestDefaults.agentRole1.copy(id = 5L, name = "zebra", displayName = "Zebra"),
            TestDefaults.agentRole1.copy(id = 6L, name = "beta", displayName = "beta"),
            TestDefaults.agentRole1.copy(id = 7L, name = "alpha", displayName = null),
            TestDefaults.agentRole1.copy(id = 8L, name = "Gamma", displayName = "   ")
        )

        val advertisement = mapper.loadSpawnableAgentsAdvertisement(userId, setOf(5L, 6L, 7L, 8L), null)

        // alpha (name fallback) < beta < Gamma (blank display name falls back to the name) < Zebra.
        assertEquals(listOf(7L, 6L, 8L, 5L), advertisement.targets.map { it.id })
    }

    /**
     * Verifies the tie-break: targets sharing a displayed label (and labels differing only in case)
     * are ordered by ascending id, so the rendered order is never ambiguous.
     */
    @Test
    fun `loadSpawnableAgentsAdvertisement breaks a shared label tie by ascending id`() = runTest {
        // Two targets share the label "Helper" (they may coexist in disjoint projects) and the third
        // only differs in case, which the sort key lower-cases away.
        coEvery { agentRoleDao.getRolesByIdsForUser(any(), any()) } returns listOf(
            TestDefaults.agentRole1.copy(id = 9L, name = "helper", displayName = "Helper"),
            TestDefaults.agentRole1.copy(id = 3L, name = "helper", displayName = "Helper"),
            TestDefaults.agentRole1.copy(id = 5L, name = "Helper", displayName = null)
        )

        val advertisement = mapper.loadSpawnableAgentsAdvertisement(userId, setOf(9L, 3L, 5L), null)

        assertEquals(listOf(3L, 5L, 9L), advertisement.targets.map { it.id })
    }
}
