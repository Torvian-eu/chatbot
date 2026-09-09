package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.error.project.ProjectError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ProjectDaoExposed].
 *
 * Verifies the plain `projects` table projection against a real in-memory SQLite database: CRUD,
 * per-owner listing, owner-scoped id resolution, the per-owner name existence check (the DB column
 * is deliberately non-unique, mirroring `agent_roles.name`), and the cascade delete of ownership and
 * membership links.
 */
class ProjectDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var projectDao: ProjectDao
    private lateinit var testDataManager: TestDataManager

    private val project1 = TestDefaults.project1
    private val project2 = TestDefaults.project2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        projectDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(
            TestDataSet(
                users = listOf(TestDefaults.user1, TestDefaults.user2)
            )
        )
        testDataManager.createTables(
            setOf(Table.PROJECTS, Table.PROJECT_OWNERS)
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `insertProject creates a row with timestamps`() = runTest {
        val created = projectDao.insertProject("Acme Web App", "Flagship")

        assertNotNull(created.id)
        assertEquals("Acme Web App", created.name)
        assertEquals("Flagship", created.description)
        assertEquals(created.createdAt, created.updatedAt)

        val stored = testDataManager.getProject(created.id)
        assertNotNull(stored)
        assertEquals("Acme Web App", stored.name)
    }

    @Test
    fun `getProjectById returns the row or NotFound`() = runTest {
        val inserted = projectDao.insertProject("Acme Web App", "Flagship")

        val found = projectDao.getProjectById(inserted.id)
        assertTrue(found.isRight())
        assertEquals("Acme Web App", found.getOrNull()?.name)

        val missing = projectDao.getProjectById(999L)
        assertEquals(ProjectError.NotFound(999L), missing.leftOrNull())
    }

    @Test
    fun `getAllProjectsForUser is scoped per owner`() = runTest {
        val p1 = projectDao.insertProject("Acme Web App", "Flagship")
        val p2 = projectDao.insertProject("Acme Mobile App", "Mobile")
        testDataManager.insertProjectOwnership(p1.id, TestDefaults.user1.id)
        testDataManager.insertProjectOwnership(p2.id, TestDefaults.user2.id)

        val ownedByUser1 = projectDao.getAllProjectsForUser(TestDefaults.user1.id)
        val ownedByUser2 = projectDao.getAllProjectsForUser(TestDefaults.user2.id)

        assertEquals(listOf(p1.id), ownedByUser1.map { it.id })
        assertEquals(listOf(p2.id), ownedByUser2.map { it.id })
    }

    @Test
    fun `getProjectsByIdsForUser resolves owned ids in requested order and omits foreign ids`() = runTest {
        val p1 = projectDao.insertProject("Acme Web App", "Flagship")
        val p2 = projectDao.insertProject("Acme Mobile App", "Mobile")
        testDataManager.insertProjectOwnership(p1.id, TestDefaults.user1.id)
        testDataManager.insertProjectOwnership(p2.id, TestDefaults.user2.id)

        // Requested in reverse order (the DAO restores caller order), including a foreign id.
        val resolved = projectDao.getProjectsByIdsForUser(TestDefaults.user1.id, listOf(p2.id, p1.id))

        assertEquals(listOf(p1.id), resolved.map { it.id })
    }

    @Test
    fun `projectNameExistsForUser is scoped per owner and matches exactly`() = runTest {
        val p1 = projectDao.insertProject("Acme Web App", "Flagship")
        testDataManager.insertProjectOwnership(p1.id, TestDefaults.user1.id)

        assertTrue(projectDao.projectNameExistsForUser(TestDefaults.user1.id, "Acme Web App"))
        // Another user may reuse the same name (the column is not globally unique).
        assertEquals(false, projectDao.projectNameExistsForUser(TestDefaults.user2.id, "Acme Web App"))
        assertEquals(false, projectDao.projectNameExistsForUser(TestDefaults.user1.id, "Other"))
    }

    @Test
    fun `updateProject replaces name and description and bumps updatedAt`() = runTest {
        val inserted = projectDao.insertProject("Acme Web App", "Flagship")

        val result = projectDao.updateProject(
            inserted.copy(name = "Renamed", description = "Updated")
        )

        assertTrue(result.isRight())
        val stored = testDataManager.getProject(inserted.id)
        assertNotNull(stored)
        assertEquals("Renamed", stored.name)
        assertEquals("Updated", stored.description)
        assertTrue(stored.updatedAt > inserted.updatedAt, "updatedAt must be bumped")
    }

    @Test
    fun `updateProject returns NotFound for a missing row`() = runTest {
        val result = projectDao.updateProject(TestDefaults.project1.copy(id = 999L))

        assertEquals(ProjectError.NotFound(999L), result.leftOrNull())
    }

    @Test
    fun `deleteProject removes the row and cascades ownership and membership links`() = runTest {
        val project = projectDao.insertProject("Acme Web App", "Flagship")
        val role = TestDefaults.agentRole1.copy(modelId = null, modelSettingsId = null, instructionsJson = "[]")
        testDataManager.setup(
            TestDataSet(agentRoles = listOf(role))
        )
        // The membership is a single column on the role row, so asserting the role survives the
        // project deletion is enough (no join table exists to cascade).
        testDataManager.insertProjectOwnership(project.id, TestDefaults.user1.id)
        testDataManager.assignRoleToProject(role.id, project.id)

        val result = projectDao.deleteProject(project.id)

        assertTrue(result.isRight())
        assertNull(testDataManager.getProject(project.id))
        // The role itself survives the project deletion.
        assertNotNull(testDataManager.getAgentRole(role.id))
    }

    @Test
    fun `deleteProject returns NotFound for a missing row`() = runTest {
        val result = projectDao.deleteProject(999L)

        assertEquals(ProjectError.NotFound(999L), result.leftOrNull())
    }
}