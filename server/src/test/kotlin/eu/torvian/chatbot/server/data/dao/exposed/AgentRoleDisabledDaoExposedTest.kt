package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AgentRoleDisabledDaoExposed].
 *
 * These tests exercise the user-scoped side-table semantics against a real in-memory SQLite database
 * managed by [TestDataManager]: a `(user, role)` row presence means disabled for that user, absence
 * means enabled, and the single-role existence check [AgentRoleDisabledDao.isRoleDisabled] must agree
 * with the batch read [AgentRoleDisabledDao.getDisabledRoleIds] while staying user-scoped.
 */
class AgentRoleDisabledDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var agentRoleDisabledDao: AgentRoleDisabledDao
    private lateinit var testDataManager: TestDataManager

    // Model/settings references are null so the role fixture needs no llm_models/model_settings
    // seeding (the agent_role_disabled table itself only references users and agent_roles).
    private val role = TestDefaults.agentRole1.copy(
        id = 1L,
        modelId = null,
        modelSettingsId = null,
        instructionsJson = "[]"
    )

    private val userA = TestDefaults.user1
    private val userB = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        agentRoleDisabledDao = container.get()
        testDataManager = container.get()

        // Seeding a role auto-creates the agent_role_disabled table (the manager infers it from the
        // dataset), keeping the test focused on the DAO instead of manual DDL.
        testDataManager.setup(
            TestDataSet(
                users = listOf(userA, userB),
                agentRoles = listOf(role)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `isRoleDisabled returns false when no marker exists`() = runTest {
        assertFalse(agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))
        assertFalse(agentRoleDisabledDao.isRoleDisabled(userB.id, role.id))
    }

    @Test
    fun `isRoleDisabled is per-user for the same role`() = runTest {
        testDataManager.insertAgentRoleDisabled(role.id, userA.id)

        assertTrue(agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))
        assertFalse(agentRoleDisabledDao.isRoleDisabled(userB.id, role.id))
    }

    @Test
    fun `setRoleDisabled true marks and false re-enables for the same user`() = runTest {
        agentRoleDisabledDao.setRoleDisabled(userA.id, role.id, disabled = true)
        assertTrue(agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))

        agentRoleDisabledDao.setRoleDisabled(userA.id, role.id, disabled = false)
        assertFalse(agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))
    }

    @Test
    fun `isRoleDisabled agrees with the batch read`() = runTest {
        testDataManager.insertAgentRoleDisabled(role.id, userA.id)

        assertEquals(setOf(role.id), agentRoleDisabledDao.getDisabledRoleIds(userA.id, listOf(role.id)))
        assertEquals(emptySet(), agentRoleDisabledDao.getDisabledRoleIds(userB.id, listOf(role.id)))
        assertTrue(agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))

        // The batch read resolves exactly the ids the single check reports, so list paths never have
        // to call the single check per role (N+1 free).
        val batch = agentRoleDisabledDao.getDisabledRoleIds(userA.id, listOf(role.id))
        assertEquals(batch.contains(role.id), agentRoleDisabledDao.isRoleDisabled(userA.id, role.id))
    }
}