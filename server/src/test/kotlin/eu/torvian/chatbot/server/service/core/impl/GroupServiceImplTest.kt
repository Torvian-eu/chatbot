package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [GroupServiceImpl].
 *
 * This test suite verifies that [GroupServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and handles business logic validation.
 * All dependencies ([GroupDao], [SessionDao], [TransactionScope]) are mocked using MockK.
 */
class GroupServiceImplTest {

    // Mocked dependencies
    private lateinit var groupDao: GroupDao
    private lateinit var groupOwnershipDao: GroupOwnershipDao
    private lateinit var sessionDao: SessionDao
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var groupService: GroupServiceImpl

    // Test data
    private val testGroup1 = ChatGroup(
        id = 1L,
        name = "Test Group 1",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L)
    )

    private val testGroup2 = ChatGroup(
        id = 2L,
        name = "Test Group 2",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L)
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        groupDao = mockk()
        groupOwnershipDao = mockk()
        sessionDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        groupService = GroupServiceImpl(groupDao, groupOwnershipDao, sessionDao, transactionScope)

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(groupDao, groupOwnershipDao, sessionDao, transactionScope)
    }

    // --- getAllGroups Tests ---

    @Test
    fun `getAllGroups should return list of groups from DAO`() = runTest {
        // Arrange
        val userId = 1L
        val expectedGroups = listOf(testGroup1, testGroup2)
        coEvery { groupOwnershipDao.getAllGroupsForUser(userId) } returns expectedGroups

        // Act
        val result = groupService.getAllGroups(userId)

        // Assert
        assertEquals(expectedGroups, result, "Should return the groups from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { groupOwnershipDao.getAllGroupsForUser(userId) }
    }

    @Test
    fun `getAllGroups should return empty list when no groups exist`() = runTest {
        // Arrange
        val userId = 1L
        coEvery { groupOwnershipDao.getAllGroupsForUser(userId) } returns emptyList()

        // Act
        val result = groupService.getAllGroups(userId)

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no groups exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { groupOwnershipDao.getAllGroupsForUser(userId) }
    }

    // --- createGroup Tests ---

    @Test
    fun `createGroup should create group successfully with valid name`() = runTest {
        // Arrange
        val userId = 1L
        val groupName = "New Group"
        coEvery { groupDao.insertGroup(groupName) } returns testGroup1
        coEvery { groupOwnershipDao.setOwner(testGroup1.id, userId) } returns Unit.right()

        // Act
        val result = groupService.createGroup(userId, groupName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testGroup1, result.getOrNull(), "Should return the created group")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { groupDao.insertGroup(groupName) }
        coVerify(exactly = 1) { groupOwnershipDao.setOwner(testGroup1.id, userId) }
    }

    @Test
    fun `createGroup should return InvalidName error for blank name`() = runTest {
        // Arrange
        val userId = 1L
        val blankName = "   "

        // Act
        val result = groupService.createGroup(userId, blankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is CreateGroupError.InvalidName, "Should be InvalidName error")
        assertEquals("Group name cannot be blank.", (error as CreateGroupError.InvalidName).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { groupDao.insertGroup(any()) }
    }

    @Test
    fun `createGroup should return InvalidName error for empty name`() = runTest {
        // Arrange
        val userId = 1L
        val emptyName = ""

        // Act
        val result = groupService.createGroup(userId, emptyName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for empty name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is CreateGroupError.InvalidName, "Should be InvalidName error")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { groupDao.insertGroup(any()) }
    }

    // --- renameGroup Tests ---

    @Test
    fun `renameGroup should rename group successfully`() = runTest {
        // Arrange
        val userId = 1L
        val groupId = 1L
        val newName = "Renamed Group"
        coEvery { groupDao.renameGroup(groupId, newName) } returns Unit.right()

        // Act
        val result = groupService.renameGroup(groupId, newName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful rename")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { groupDao.renameGroup(groupId, newName) }
    }

    @Test
    fun `renameGroup should return InvalidName error for blank new name`() = runTest {
        // Arrange
        val groupId = 1L
        val blankName = "  "

        // Act
        val result = groupService.renameGroup(groupId, blankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is RenameGroupError.InvalidName, "Should be InvalidName error")
        assertEquals("New group name cannot be blank.", (error as RenameGroupError.InvalidName).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { groupDao.renameGroup(any(), any()) }
    }

    @Test
    fun `renameGroup should return GroupNotFound error when group does not exist`() = runTest {
        // Arrange
        val groupId = 999L
        val newName = "New Name"
        val daoErr = GroupError.GroupNotFound(groupId)
        coEvery { groupDao.renameGroup(groupId, newName) } returns daoErr.left()

        // Act
        val result = groupService.renameGroup(groupId, newName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent group")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is RenameGroupError.GroupNotFound, "Should be GroupNotFound error")
        assertEquals(groupId, (error as RenameGroupError.GroupNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { groupDao.renameGroup(groupId, newName) }
    }

    // --- deleteGroup Tests ---

    @Test
    fun `deleteGroup should delete group successfully and ungroup sessions`() = runTest {
        // Arrange
        val userId = 1L
        val groupId = 1L
        coEvery { sessionDao.ungroupSessions(groupId) } returns Unit
        coEvery { groupDao.deleteGroup(groupId) } returns Unit.right()

        // Act
        val result = groupService.deleteGroup(groupId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.ungroupSessions(groupId) }
        coVerify(exactly = 1) { groupDao.deleteGroup(groupId) }
    }

    @Test
    fun `deleteGroup should return GroupNotFound error when group does not exist`() = runTest {
        // Arrange
        val groupId = 999L
        val daoError = GroupError.GroupNotFound(groupId)
        coEvery { sessionDao.ungroupSessions(groupId) } returns Unit
        coEvery { groupDao.deleteGroup(groupId) } returns daoError.left()

        // Act
        val result = groupService.deleteGroup(groupId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent group")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteGroupError.GroupNotFound, "Should be GroupNotFound error")
        assertEquals(groupId, (error as DeleteGroupError.GroupNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.ungroupSessions(groupId) }
        coVerify(exactly = 1) { groupDao.deleteGroup(groupId) }
    }

    @Test
    fun `deleteGroup should still ungroup sessions even when group deletion fails`() = runTest {
        // Arrange
        val groupId = 1L
        val daoError = GroupError.GroupNotFound(groupId)
        coEvery { sessionDao.ungroupSessions(groupId) } returns Unit
        coEvery { groupDao.deleteGroup(groupId) } returns daoError.left()

        // Act
        val result = groupService.deleteGroup(groupId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left when deletion fails")
        // Verify that ungroupSessions was still called before attempting deletion
        coVerifyOrder {
            sessionDao.ungroupSessions(groupId)
            groupDao.deleteGroup(groupId)
        }
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
    }
}
