package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.error.usergroup.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.usergroup.RemoveUserFromGroupError
import eu.torvian.chatbot.server.service.core.error.usergroup.UpdateGroupError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UserGroupServiceImpl].
 *
 * This test suite verifies user group management functionality:
 * - Creating and managing user groups
 * - Special "All Users" group behavior
 * - Protection of special groups from deletion/modification
 * - User membership management
 */
class UserGroupServiceImplTest {
    private lateinit var container: DIContainer
    private lateinit var userGroupService: UserGroupService
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        userGroupService = container.get()
        testDataManager = container.get()

        // Create necessary tables
        testDataManager.createTables(
            setOf(
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS,
                Table.USERS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `createGroup should create a new group successfully`() = runTest {
        val name = "Test Group"
        val description = "A test group"

        val result = userGroupService.createGroup(name, description)

        assertTrue(result.isRight(), "Expected successful group creation")
        val group = result.getOrNull()!!
        assertNotNull(group.id)
        assertEquals(name, group.name)
        assertEquals(description, group.description)
    }

    @Test
    fun `createGroup should create All Users group successfully`() = runTest {
        val result = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )

        assertTrue(result.isRight(), "Expected successful All Users group creation")
        val group = result.getOrNull()!!
        assertEquals(CommonUserGroups.ALL_USERS, group.name)
    }

    @Test
    fun `getGroupByName should retrieve All Users group`() = runTest {
        // First create the group
        userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )

        // Then retrieve it
        val result = userGroupService.getGroupByName(CommonUserGroups.ALL_USERS)

        assertTrue(result.isRight(), "Expected to find All Users group")
        val group = result.getOrNull()!!
        assertEquals(CommonUserGroups.ALL_USERS, group.name)
    }

    @Test
    fun `getAllUsersGroup should retrieve All Users group`() = runTest {
        // First create the group
        userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )

        // Then retrieve it using the convenience method
        val result = userGroupService.getAllUsersGroup()

        assertTrue(result.isRight(), "Expected to find All Users group")
        val group = result.getOrNull()!!
        assertEquals(CommonUserGroups.ALL_USERS, group.name)
    }

    @Test
    fun `deleteGroup should prevent deletion of All Users group`() = runTest {
        // Create the All Users group
        val createResult = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
        val group = createResult.getOrNull()!!

        // Try to delete it
        val deleteResult = userGroupService.deleteGroup(group.id)

        assertTrue(deleteResult.isLeft(), "Expected deletion to fail")
        val error = deleteResult.leftOrNull()
        assertIs<DeleteGroupError.InvalidOperation>(error, "Expected InvalidOperation error")
    }

    @Test
    fun `updateGroup should prevent renaming All Users group`() = runTest {
        // Create the All Users group
        val createResult = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
        val group = createResult.getOrNull()!!

        // Try to rename it
        val updateResult = userGroupService.updateGroup(
            groupId = group.id,
            name = "New Name",
            description = "New description"
        )

        assertTrue(updateResult.isLeft(), "Expected update to fail")
        val error = updateResult.leftOrNull()
        assertIs<UpdateGroupError.InvalidOperation>(error, "Expected InvalidOperation error")
    }

    @Test
    fun `updateGroup should allow updating description of All Users group`() = runTest {
        // Create the All Users group
        val createResult = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Original description"
        )
        val group = createResult.getOrNull()!!

        // Update description while keeping the name
        val updateResult = userGroupService.updateGroup(
            groupId = group.id,
            name = CommonUserGroups.ALL_USERS,
            description = "Updated description"
        )

        assertTrue(updateResult.isRight(), "Expected update to succeed")

        // Verify the description was updated
        val getResult = userGroupService.getGroupById(group.id)
        val updatedGroup = getResult.getOrNull()!!
        assertEquals(CommonUserGroups.ALL_USERS, updatedGroup.name)
        assertEquals("Updated description", updatedGroup.description)
    }

    @Test
    fun `removeUserFromGroup should prevent removal from All Users group`() = runTest {
        // Create the All Users group
        val createResult = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
        val group = createResult.getOrNull()!!

        // Create a test user using UserDao
        val userDao = container.get<eu.torvian.chatbot.server.data.dao.UserDao>()
        val userResult = userDao.insertUser(
            username = "testuser",
            passwordHash = "hash",
            email = null,
            status = eu.torvian.chatbot.common.models.user.UserStatus.ACTIVE
        )
        val user = userResult.getOrNull()!!

        userGroupService.addUserToGroup(user.id, group.id)

        // Try to remove user from All Users group
        val removeResult = userGroupService.removeUserFromGroup(user.id, group.id)

        assertTrue(removeResult.isLeft(), "Expected removal to fail")
        val error = removeResult.leftOrNull()
        assertIs<RemoveUserFromGroupError.InvalidOperation>(error, "Expected InvalidOperation error")
    }

    @Test
    fun `deleteGroup should allow deletion of normal groups`() = runTest {
        // Create a normal group
        val createResult = userGroupService.createGroup(
            name = "Normal Group",
            description = "A deletable group"
        )
        val group = createResult.getOrNull()!!

        // Delete it
        val deleteResult = userGroupService.deleteGroup(group.id)

        assertTrue(deleteResult.isRight(), "Expected successful deletion")

        // Verify it's gone
        val getResult = userGroupService.getGroupById(group.id)
        assertTrue(getResult.isLeft(), "Expected group to be deleted")
    }

    @Test
    fun `addUserToGroup should add user to All Users group successfully`() = runTest {
        // Create the All Users group
        val createResult = userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
        val group = createResult.getOrNull()!!

        // Create a test user using UserDao
        val userDao = container.get<eu.torvian.chatbot.server.data.dao.UserDao>()
        val userResult = userDao.insertUser(
            username = "testuser",
            passwordHash = "hash",
            email = null,
            status = eu.torvian.chatbot.common.models.user.UserStatus.ACTIVE
        )
        val user = userResult.getOrNull()!!

        // Add user to All Users group
        val addResult = userGroupService.addUserToGroup(user.id, group.id)

        assertTrue(addResult.isRight(), "Expected successful addition")
    }

    @Test
    fun `getAllGroups should include All Users group`() = runTest {
        // Create the All Users group and a normal group
        userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
        userGroupService.createGroup(
            name = "Normal Group",
            description = "A normal group"
        )

        // Get all groups
        val groups = userGroupService.getAllGroups()

        assertEquals(2, groups.size, "Expected two groups")
        assertTrue(
            groups.any { it.name == CommonUserGroups.ALL_USERS },
            "Expected All Users group to be in the list"
        )
        assertTrue(
            groups.any { it.name == "Normal Group" },
            "Expected Normal Group to be in the list"
        )
    }
}
