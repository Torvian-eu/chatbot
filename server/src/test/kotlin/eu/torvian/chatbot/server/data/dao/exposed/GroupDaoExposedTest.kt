package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.test.assertIs

/**
 * Tests for [GroupDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [GroupDao]:
 * - Getting all groups
 * - Getting a group by ID
 * - Inserting a new group
 * - Renaming a group
 * - Deleting a group
 * - Handling error cases (group not found)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class GroupDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var groupDao: GroupDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testGroup1 = ChatGroup(
        id = 1,
        name = "Test Group 1",
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS)
    )

    private val testGroup2 = ChatGroup(
        id = 2,
        name = "Test Group 2",
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS)
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        groupDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.CHAT_GROUPS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllGroups should return empty list when no groups exist`() = runTest {
        val groups = groupDao.getAllGroups()
        assertTrue(groups.isEmpty(), "Expected empty list when no groups exist")
    }

    @Test
    fun `getAllGroups should return all groups when groups exist`() = runTest {
        // Insert test groups
        groupDao.insertGroup(testGroup1.name)
        groupDao.insertGroup(testGroup2.name)

        // Get all groups
        val groups = groupDao.getAllGroups()
        
        // Verify
        assertEquals(2, groups.size, "Expected 2 groups")
        assertTrue(groups.any { it.name == testGroup1.name }, "Expected to find group with name ${testGroup1.name}")
        assertTrue(groups.any { it.name == testGroup2.name }, "Expected to find group with name ${testGroup2.name}")
    }

    @Test
    fun `getGroupById should return group when it exists`() = runTest {
        // Insert a test group
        val insertedGroup = groupDao.insertGroup(testGroup1.name)
        assertNotNull(insertedGroup, "Failed to insert test group")

        // Get the group by ID
        val result = groupDao.getGroupById(insertedGroup.id)
        
        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing group")
        val group = result.getOrNull()
        assertNotNull(group, "Expected non-null group")
        assertEquals(insertedGroup.id, group.id, "Expected matching ID")
        assertEquals(testGroup1.name, group.name, "Expected matching name")
    }

    @Test
    fun `getGroupById should return GroupNotFound when group does not exist`() = runTest {
        // Get a non-existent group
        val result = groupDao.getGroupById(999)
        
        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent group")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<GroupError.GroupNotFound>(error, "Expected GroupNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `insertGroup should insert a new group`() = runTest {
        // Insert a new group
        val group = groupDao.insertGroup(testGroup1.name)

        // Verify
        assertNotNull(group, "Expected non-null group")
        assertEquals(testGroup1.name, group.name, "Expected matching name")
        assertNotNull(group.id, "Expected non-null ID")
        assertNotNull(group.createdAt, "Expected non-null createdAt")
    }

    @Test
    fun `insertGroup should allow multiple groups with the same name`() = runTest {
        // Insert first group with the name
        val firstGroup = groupDao.insertGroup(testGroup1.name)
        assertNotNull(firstGroup, "First insertion failed")

        // Insert second group with the same name
        val secondGroup = groupDao.insertGroup(testGroup1.name)

        // Verify
        assertNotNull(secondGroup, "Second insertion with same name failed")
        assertEquals(testGroup1.name, secondGroup.name, "Expected matching name")
        assertNotEquals(firstGroup.id, secondGroup.id, "Expected different IDs for groups with same name")
    }

    @Test
    fun `renameGroup should rename an existing group`() = runTest {
        // Insert a group
        val insertedGroup = groupDao.insertGroup(testGroup1.name)
        assertNotNull(insertedGroup, "Failed to insert test group")
        
        // Rename the group
        val newName = "Renamed Group"
        val result = groupDao.renameGroup(insertedGroup.id, newName)
        
        // Verify
        assertTrue(result.isRight(), "Expected Right result for successful rename")
        
        // Verify the group was renamed
        val getResult = groupDao.getGroupById(insertedGroup.id)
        assertTrue(getResult.isRight(), "Expected to find renamed group")
        val renamedGroup = getResult.getOrNull()
        assertNotNull(renamedGroup, "Expected non-null group")
        assertEquals(newName, renamedGroup.name, "Expected updated name")
    }

    @Test
    fun `renameGroup should return GroupNotFound when group does not exist`() = runTest {
        // Try to rename a non-existent group
        val result = groupDao.renameGroup(999, "New Name")
        
        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent group")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<GroupError.GroupNotFound>(error, "Expected GroupNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `renameGroup should allow renaming to an existing name`() = runTest {
        // Insert two groups with different names
        val group1 = groupDao.insertGroup(testGroup1.name)
        val group2 = groupDao.insertGroup(testGroup2.name)

        // Rename group2 to have the same name as group1
        val result = groupDao.renameGroup(group2.id, group1.name)
        
        // Verify rename was successful
        assertTrue(result.isRight(), "Expected Right result for rename to existing name")

        // Verify the group was renamed
        val getResult = groupDao.getGroupById(group2.id)
        assertTrue(getResult.isRight(), "Expected to find renamed group")
        val renamedGroup = getResult.getOrNull()
        assertNotNull(renamedGroup, "Expected non-null group")
        assertEquals(group1.name, renamedGroup.name, "Expected updated name to match existing group name")
    }

    @Test
    fun `deleteGroup should delete an existing group`() = runTest {
        // Insert a group
        val insertedGroup = groupDao.insertGroup(testGroup1.name)
        assertNotNull(insertedGroup, "Failed to insert test group")
        
        // Delete the group
        val result = groupDao.deleteGroup(insertedGroup.id)
        
        // Verify
        assertTrue(result.isRight(), "Expected Right result for successful deletion")
        
        // Verify the group was deleted
        val getResult = groupDao.getGroupById(insertedGroup.id)
        assertTrue(getResult.isLeft(), "Expected group to be deleted")
        val error = getResult.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<GroupError.GroupNotFound>(error, "Expected GroupNotFound error")
    }

    @Test
    fun `deleteGroup should return GroupNotFound when group does not exist`() = runTest {
        // Try to delete a non-existent group
        val result = groupDao.deleteGroup(999)
        
        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent group")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<GroupError.GroupNotFound>(error, "Expected GroupNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }
}