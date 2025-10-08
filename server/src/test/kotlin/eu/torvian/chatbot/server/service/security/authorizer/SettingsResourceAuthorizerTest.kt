package eu.torvian.chatbot.server.service.security.authorizer

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsResourceAuthorizerTest {
    private val settingsOwnershipDao: SettingsOwnershipDao = mockk()
    private val settingsAccessDao: SettingsAccessDao = mockk()
    private val userGroupDao: UserGroupDao = mockk()

    private val authorizer = SettingsResourceAuthorizer(
        settingsOwnershipDao = settingsOwnershipDao,
        settingsAccessDao = settingsAccessDao,
        userGroupDao = userGroupDao
    )

    @Test
    fun `owner has full READ access to settings`() = runTest {
        val userId = 1L
        val settingsId = 100L

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns userId.right()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `owner has full WRITE access to settings`() = runTest {
        val userId = 1L
        val settingsId = 100L

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns userId.right()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner with group READ access can read`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(groupId), "read") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner with group WRITE access can write`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(groupId), "write") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner without group access is denied`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(groupId), "read") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizerError.AccessDenied)
            assertTrue((error as ResourceAuthorizerError.AccessDenied).reason.contains("does not have read access"))
        }
    }

    @Test
    fun `non-owner with READ access is denied WRITE`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(groupId), "write") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizerError.AccessDenied)
        }
    }

    @Test
    fun `user in multiple groups with one having access is granted`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val group1 = UserGroupEntity(id = 10L, name = "Group1", description = null)
        val group2 = UserGroupEntity(id = 20L, name = "Group2", description = null)

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group1, group2)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(10L, 20L), "read") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `user not in any groups is denied access`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns emptyList()
        coEvery { settingsAccessDao.hasAccess(settingsId, emptyList(), "read") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizerError.AccessDenied)
        }
    }

    @Test
    fun `non-existent settings returns ResourceNotFound`() = runTest {
        val userId = 1L
        val settingsId = 999L

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns
            GetOwnerError.ResourceNotFound("Settings not found").left()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizerError.ResourceNotFound)
            assertEquals(settingsId, (error as ResourceAuthorizerError.ResourceNotFound).id)
        }
    }

    @Test
    fun `custom access mode is properly passed to DAO`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val settingsId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )
        val customMode = AccessMode.of("manage")

        coEvery { settingsOwnershipDao.getOwner(settingsId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { settingsAccessDao.hasAccess(settingsId, listOf(groupId), "manage") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = settingsId,
            accessMode = customMode
        )

        assertTrue(result.isRight())
    }
}
