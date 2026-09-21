package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the spawn-allow-list advertisement plumbing of [AgentRoleServiceImpl]: the `spawnable_agents`
 * instruction is rendered from the role's allow-list plus its own project, using a single batched project
 * read that covers the targets' projects and the role's project together.
 */
class AgentRoleServiceImplSpawnAdvertisementTest : AgentRoleServiceImplTestBase() {

    /** Id of the `spawn_agent` operator tool attached to the role under test. */
    private val spawnToolId = 21L

    /**
     * Callable operator tool named `spawn_agent`, so the advertisement knows the section must be rendered.
     */
    private val spawnTool = OperatorToolDefinition(
        id = spawnToolId,
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

    @Test
    fun `getRoleById renders the advertisement with one batched project read`() = runTest {
        val roleId = 1L
        val instructionsJson = """[{"type":"spawnable_agents","name":"Available agents","message":""}]"""
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(
            id = roleId,
            instructionsJson = instructionsJson,
            // The role itself lives in a third project, so the batched read must cover all three ids.
            projectId = 3L
        ).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns setOf(spawnToolId)
        coEvery { toolDefinitionDao.getToolDefinitionsByIds(setOf(spawnToolId)) } returns listOf(spawnTool)
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns setOf(5L, 6L)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L, 6L)) } returns listOf(
            TestDefaults.agentRole1.copy(id = 5L, name = "writer", displayName = "Writer", projectId = 1L),
            TestDefaults.agentRole2.copy(id = 6L, name = "reviewer", displayName = null, projectId = 2L)
        )
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L, 2L, 3L)) } returns listOf(
            TestDefaults.project1.copy(id = 1L, name = "Web"),
            TestDefaults.project2.copy(id = 2L, name = "Infra"),
            TestDefaults.project1.copy(id = 3L, name = "Ops")
        )

        val result = service.getRoleById(userId, roleId)

        val message = result.getOrNull()?.instructions?.single()?.message.orEmpty()
        // The intro names the role's own project, and each row names its target's project.
        assertTrue(message.contains("The current project is Ops (id 3)."), message)
        assertTrue(message.contains("| 5 | Writer | Web (id 1) |"), message)
        assertTrue(message.contains("| 6 | reviewer | Infra (id 2) |"), message)
        // One batched project read backs the whole advertisement (no per-target query), and the target read
        // happens once as well.
        coVerify(exactly = 1) { projectDao.getProjectsByIdsForUser(userId, listOf(1L, 2L, 3L)) }
        coVerify(exactly = 1) { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L, 6L)) }
    }

    @Test
    fun `getRoleById renders an unassociated advertisement without a project read`() = runTest {
        val roleId = 1L
        val instructionsJson = """[{"type":"spawnable_agents","name":"Available agents","message":""}]"""
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(
            id = roleId,
            instructionsJson = instructionsJson,
            projectId = null
        ).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns setOf(spawnToolId)
        coEvery { toolDefinitionDao.getToolDefinitionsByIds(setOf(spawnToolId)) } returns listOf(spawnTool)
        // An empty allow-list with an unassociated role has no project to resolve at all.
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns emptySet()
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, emptyList()) } returns emptyList()

        val result = service.getRoleById(userId, roleId)

        val message = result.getOrNull()?.instructions?.single()?.message.orEmpty()
        assertTrue(message.contains("The current project is none."), message)
        assertTrue(message.contains("No agent roles are available for you to spawn."), message)
        // Nothing is listed, so the intro must not refer to a table or to a role id to pass.
        assertFalse(message.contains("`agent_role_id`"), message)
        // Nothing to resolve means no project read is issued (the empty target read just proves the
        // allow-list is empty).
        coVerify(exactly = 0) { projectDao.getProjectsByIdsForUser(any(), any()) }
        coVerify(exactly = 1) { agentRoleDao.getRolesByIdsForUser(userId, emptyList()) }
    }

    @Test
    fun `getRoleById skips the allow-list reads when spawn_agent is not available`() = runTest {
        val roleId = 1L
        val instructionsJson = """[{"type":"spawnable_agents","name":"Available agents","message":""}]"""
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(
            id = roleId,
            instructionsJson = instructionsJson,
            projectId = 3L
        ).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        // The role carries an allow-list but no tools, so `spawn_agent` is not available to it.
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { toolDefinitionDao.getToolDefinitionsByIds(emptySet()) } returns emptyList()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(roleId) } returns setOf(5L, 6L)

        val result = service.getRoleById(userId, roleId)

        assertEquals("", result.getOrNull()?.instructions?.single()?.message)
        // The omitted section costs nothing: neither the target read nor the batched project read is
        // issued for it.
        coVerify(exactly = 0) { agentRoleDao.getRolesByIdsForUser(any(), any()) }
        coVerify(exactly = 0) { projectDao.getProjectsByIdsForUser(any(), any()) }
    }
}
