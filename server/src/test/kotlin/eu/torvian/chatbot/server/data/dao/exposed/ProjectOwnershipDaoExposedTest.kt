package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.ProjectOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [ProjectOwnershipDaoExposed].
 *
 * Verifies the `project_owners` single-owner semantics against a real in-memory SQLite database:
 * exactly one owner per project (project_id is the primary key), the not-found/conflict error
 * mapping, and the cascade delete of ownership rows when the project or the user is deleted.
 */
class ProjectOwnershipDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var projectOwnershipDao: ProjectOwnershipDao
    private lateinit var projectDao: ProjectDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        projectOwnershipDao = container.get()
        projectDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(
            TestDataSet(
                users = listOf(TestDefaults.user1, TestDefaults.user2),
                projects = listOf(TestDefaults.project1)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `setOwner then getOwner round-trips the owning user`() = runTest {
        val result = projectOwnershipDao.setOwner(TestDefaults.project1.id, TestDefaults.user1.id)

        assertEquals(Unit, result.getOrNull())
        assertEquals(TestDefaults.user1.id, projectOwnershipDao.getOwner(TestDefaults.project1.id).getOrNull())
    }

    @Test
    fun `getOwner returns ResourceNotFound when no ownership row exists`() = runTest {
        val result = projectOwnershipDao.getOwner(TestDefaults.project1.id)

        val error = assertIs<GetOwnerError>(result.leftOrNull())
        assertEquals(
            GetOwnerError.ResourceNotFound(TestDefaults.project1.id.toString()),
            error
        )
    }

    @Test
    fun `a second owner for the same project is rejected as AlreadyOwned`() = runTest {
        projectOwnershipDao.setOwner(TestDefaults.project1.id, TestDefaults.user1.id)

        val result = projectOwnershipDao.setOwner(TestDefaults.project1.id, TestDefaults.user2.id)

        assertIs<SetOwnerError.AlreadyOwned>(result.leftOrNull())
    }

    @Test
    fun `deleting the project cascades its ownership row`() = runTest {
        projectOwnershipDao.setOwner(TestDefaults.project1.id, TestDefaults.user1.id)

        projectDao.deleteProject(TestDefaults.project1.id)

        val result = projectOwnershipDao.getOwner(TestDefaults.project1.id)
        assertIs<GetOwnerError>(result.leftOrNull())
    }
}