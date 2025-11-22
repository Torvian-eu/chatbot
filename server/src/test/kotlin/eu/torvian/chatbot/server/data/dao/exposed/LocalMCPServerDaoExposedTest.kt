package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LocalMCPServerDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [LocalMCPServerDao]:
 * - Generating unique server IDs
 * - Deleting servers by ID (success and NotFound cases)
 * - Getting server IDs by user ID
 * - Checking server existence
 * - Validating server ownership (authorized and unauthorized cases)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class LocalMCPServerDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var dao: LocalMCPServerDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testUser1 = TestDefaults.user1
    private val testUser2 = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        dao = container.get()
        testDataManager = container.get()

        // Create required tables
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.LOCAL_MCP_SERVERS
            )
        )

        // Setup test users
        testDataManager.setup(
            TestDataSet(
                users = listOf(testUser1, testUser2)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `generateId should create new entry and return ID`() = runTest {
        // Act
        val serverId = dao.generateId(testUser1.id)

        // Assert
        assertTrue(serverId > 0, "Generated ID should be positive")
        assertTrue(dao.existsById(serverId), "Server should exist")
    }

    @Test
    fun `generateId should create multiple IDs for same user`() = runTest {
        // Act
        val serverId1 = dao.generateId(testUser1.id)
        val serverId2 = dao.generateId(testUser1.id)

        // Assert
        assertTrue(serverId1 > 0, "First generated ID should be positive")
        assertTrue(serverId2 > 0, "Second generated ID should be positive")
        assertTrue(serverId1 != serverId2, "Generated IDs should be unique")
        assertTrue(dao.existsById(serverId1), "First server should exist")
        assertTrue(dao.existsById(serverId2), "Second server should exist")
    }

    @Test
    fun `deleteById should delete existing server`() = runTest {
        // Arrange
        val serverId = dao.generateId(testUser1.id)

        // Act
        val result = dao.deleteById(serverId)

        // Assert
        assertTrue(result.isRight(), "Deletion should succeed")
        assertFalse(dao.existsById(serverId), "Server should not exist after deletion")
    }

    @Test
    fun `deleteById should return NotFound when server doesn't exist`() = runTest {
        // Act
        val result = dao.deleteById(999L)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertTrue(
            result.leftOrNull() is DeleteLocalMCPServerError.NotFound,
            "Should be NotFound error"
        )
    }

    @Test
    fun `getIdsByUserId should return all server IDs for user`() = runTest {
        // Arrange
        val serverId1 = dao.generateId(testUser1.id)
        val serverId2 = dao.generateId(testUser1.id)

        // Act
        val serverIds = dao.getIdsByUserId(testUser1.id)

        // Assert
        assertEquals(2, serverIds.size)
        assertTrue(serverIds.contains(serverId1))
        assertTrue(serverIds.contains(serverId2))
    }

    @Test
    fun `getIdsByUserId should return only servers for specified user`() = runTest {
        // Arrange
        val user1ServerId = dao.generateId(testUser1.id)
        val user2ServerId = dao.generateId(testUser2.id)

        // Act
        val user1ServerIds = dao.getIdsByUserId(testUser1.id)
        val user2ServerIds = dao.getIdsByUserId(testUser2.id)

        // Assert
        assertEquals(1, user1ServerIds.size)
        assertEquals(1, user2ServerIds.size)
        assertTrue(user1ServerIds.contains(user1ServerId))
        assertFalse(user1ServerIds.contains(user2ServerId))
        assertTrue(user2ServerIds.contains(user2ServerId))
        assertFalse(user2ServerIds.contains(user1ServerId))
    }

    @Test
    fun `getIdsByUserId should return empty list when user has no servers`() = runTest {
        // Act
        val serverIds = dao.getIdsByUserId(testUser1.id)

        // Assert
        assertEquals(0, serverIds.size)
    }

    @Test
    fun `existsById should return true when server exists`() = runTest {
        // Arrange
        val serverId = dao.generateId(testUser1.id)

        // Act & Assert
        assertTrue(dao.existsById(serverId))
    }

    @Test
    fun `existsById should return false when server doesn't exist`() = runTest {
        // Act & Assert
        assertFalse(dao.existsById(999L))
    }

    @Test
    fun `validateOwnership should succeed when user owns server`() = runTest {
        // Arrange
        val serverId = dao.generateId(testUser1.id)

        // Act
        val result = dao.validateOwnership(testUser1.id, serverId)

        // Assert
        assertTrue(result.isRight(), "Validation should succeed")
    }

    @Test
    fun `validateOwnership should return Unauthorized when user doesn't own server`() = runTest {
        // Arrange
        val serverId = dao.generateId(testUser1.id)

        // Act
        val result = dao.validateOwnership(testUser2.id, serverId)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertTrue(
            result.leftOrNull() is LocalMCPServerError.Unauthorized,
            "Should be Unauthorized error"
        )
    }

    @Test
    fun `validateOwnership should return Unauthorized when server doesn't exist`() = runTest {
        // Act
        val result = dao.validateOwnership(testUser1.id, 999L)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertTrue(
            result.leftOrNull() is LocalMCPServerError.Unauthorized,
            "Should be Unauthorized error"
        )
    }
}

