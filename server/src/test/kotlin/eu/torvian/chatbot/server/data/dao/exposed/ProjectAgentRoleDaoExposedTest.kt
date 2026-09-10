package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectAgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ProjectAgentRoleDaoExposed].
 *
 * Verifies the single-column project membership (`agent_roles.project_id`) against a real in-memory
 * SQLite database: project-side role sets (single + batch reads), the full-replacement write
 * semantics from the project side (attach/detach via column updates), and the FK behaviors — a role
 * deletion removes its row (and thus its membership), a project deletion unassociates its roles
 * (`ON DELETE SET NULL`).
 */
class ProjectAgentRoleDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var projectAgentRoleDao: ProjectAgentRoleDao
    private lateinit var projectDao: ProjectDao
    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var testDataManager: TestDataManager

    // Preset-less roles so no llm_models/model_settings/model_presets seeding is needed.
    private val role1 = TestDefaults.agentRole1.copy(
        id = 1L,
        modelPresetId = null,
        instructionsJson = "[]"
    )
    private val role2 = TestDefaults.agentRole2.copy(
        id = 2L,
        modelPresetId = null,
        instructionsJson = "[]"
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        projectAgentRoleDao = container.get()
        projectDao = container.get()
        agentRoleDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(
            TestDataSet(
                projects = listOf(TestDefaults.project1, TestDefaults.project2),
                agentRoles = listOf(role1, role2)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `project-side reads agree with the role-side column in both directions`() = runTest {
        // A role belongs to at most one project; assigning moves it (single-column membership).
        testDataManager.assignRoleToProject(role1.id, TestDefaults.project1.id)
        testDataManager.assignRoleToProject(role2.id, TestDefaults.project1.id)
        testDataManager.assignRoleToProject(role1.id, TestDefaults.project2.id)

        // Role side: the membership rides the role entity's single projectId.
        assertEquals(
            TestDefaults.project2.id,
            agentRoleDao.getRoleById(role1.id).getOrNull()?.projectId
        )
        assertEquals(
            TestDefaults.project1.id,
            agentRoleDao.getRoleById(role2.id).getOrNull()?.projectId
        )
        // Project side: sets of member role ids, batch and single reads agreeing.
        assertEquals(setOf(role2.id), projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id))
        assertEquals(setOf(role1.id), projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project2.id))
        assertEquals(
            mapOf(
                TestDefaults.project1.id to setOf(role2.id),
                TestDefaults.project2.id to setOf(role1.id)
            ),
            projectAgentRoleDao.getRoleIdsForProjects(listOf(TestDefaults.project1.id, TestDefaults.project2.id))
        )
    }

    @Test
    fun `unassociated roles and empty projects report empty sets`() = runTest {
        assertTrue(projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id).isEmpty())
        assertTrue(projectAgentRoleDao.getRoleIdsForProjects(listOf(TestDefaults.project1.id)).isEmpty())
        assertNull(agentRoleDao.getRoleById(role1.id).getOrNull()?.projectId)
    }

    @Test
    fun `replaceRolesForProject performs a full replacement from the project side`() = runTest {
        projectAgentRoleDao.replaceRolesForProject(
            TestDefaults.project1.id,
            setOf(role1.id, role2.id)
        )

        assertEquals(setOf(role1.id, role2.id), projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id))

        // Replacement detaches the removed role member before attaching the new one.
        projectAgentRoleDao.replaceRolesForProject(TestDefaults.project1.id, setOf(role2.id))
        assertEquals(setOf(role2.id), projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id))
        assertNull(agentRoleDao.getRoleById(role1.id).getOrNull()?.projectId)

        // An empty replacement unassociates every member of the project.
        projectAgentRoleDao.replaceRolesForProject(TestDefaults.project1.id, emptySet())
        assertTrue(projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id).isEmpty())
        assertNull(agentRoleDao.getRoleById(role2.id).getOrNull()?.projectId)
    }

    @Test
    fun `deleting a role removes its membership but not the projects`() = runTest {
        testDataManager.assignRoleToProject(role1.id, TestDefaults.project1.id)
        testDataManager.assignRoleToProject(role2.id, TestDefaults.project2.id)

        agentRoleDao.deleteRole(role1.id)

        assertTrue(projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id).isEmpty())
        // The projects survive the role deletion.
        assertTrue(projectDao.getProjectById(TestDefaults.project1.id).isRight())
        assertTrue(projectDao.getProjectById(TestDefaults.project2.id).isRight())
    }

    @Test
    fun `deleting a project unassociates its roles without deleting them`() = runTest {
        testDataManager.assignRoleToProject(role1.id, TestDefaults.project1.id)
        testDataManager.assignRoleToProject(role2.id, TestDefaults.project1.id)

        projectDao.deleteProject(TestDefaults.project1.id)

        // ON DELETE SET NULL: the project's former members become unassociated roles.
        assertTrue(projectAgentRoleDao.getRoleIdsForProject(TestDefaults.project1.id).isEmpty())
        assertNull(agentRoleDao.getRoleById(role1.id).getOrNull()?.projectId)
        assertNull(agentRoleDao.getRoleById(role2.id).getOrNull()?.projectId)
        assertNull(testDataManager.getProject(TestDefaults.project1.id))
    }
}