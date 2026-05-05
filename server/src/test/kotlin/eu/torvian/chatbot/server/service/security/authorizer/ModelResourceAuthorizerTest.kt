package eu.torvian.chatbot.server.service.security.authorizer

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.server.data.dao.ModelAccessDao
import eu.torvian.chatbot.server.data.dao.ModelOwnershipDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelResourceAuthorizerTest {
    private val modelOwnershipDao: ModelOwnershipDao = mockk()
    private val modelAccessDao: ModelAccessDao = mockk()
    private val userGroupDao: UserGroupDao = mockk()

    private val authorizer = ModelResourceAuthorizer(
        modelOwnershipDao = modelOwnershipDao,
        modelAccessDao = modelAccessDao,
        userGroupDao = userGroupDao
    )

    @Test
    fun `owner has full READ access to model`() = runTest {
        val userId = 1L
        val modelId = 100L

        coEvery { modelOwnershipDao.getOwner(modelId) } returns userId.right()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `owner has full WRITE access to model`() = runTest {
        val userId = 1L
        val modelId = 100L

        coEvery { modelOwnershipDao.getOwner(modelId) } returns userId.right()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner with group READ access can read`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(groupId), "read") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner with group WRITE access can write`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(groupId), "write") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `non-owner without group access is denied`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(groupId), "read") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertIs<ResourceAuthorizerError.AccessDenied>(error)
            assertTrue(error.reason.contains("does not have read access"))
        }
    }

    @Test
    fun `non-owner with READ access is denied WRITE`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(groupId), "write") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.WRITE
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertIs<ResourceAuthorizerError.AccessDenied>(error)
        }
    }

    @Test
    fun `user in multiple groups with one having access is granted`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val group1 = UserGroupEntity(id = 10L, name = "Group1", description = null)
        val group2 = UserGroupEntity(id = 20L, name = "Group2", description = null)

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group1, group2)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(10L, 20L), "read") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `user not in any groups is denied access`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns emptyList()
        coEvery { modelAccessDao.hasAccess(modelId, emptyList(), "read") } returns false

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertIs<ResourceAuthorizerError.AccessDenied>(error)
        }
    }

    @Test
    fun `non-existent model returns ResourceNotFound`() = runTest {
        val userId = 1L
        val modelId = 999L

        coEvery { modelOwnershipDao.getOwner(modelId) } returns
            GetOwnerError.ResourceNotFound("Model not found").left()

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = AccessMode.READ
        )

        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertIs<ResourceAuthorizerError.ResourceNotFound>(error)
            assertEquals(modelId, error.id)
        }
    }

    @Test
    fun `custom access mode is properly passed to DAO`() = runTest {
        val userId = 1L
        val ownerId = 2L
        val modelId = 100L
        val groupId = 10L
        val group = UserGroupEntity(
            id = groupId,
            name = "TestGroup",
            description = null
        )
        val customMode = AccessMode.of("execute")

        coEvery { modelOwnershipDao.getOwner(modelId) } returns ownerId.right()
        coEvery { userGroupDao.getGroupsForUser(userId) } returns listOf(group)
        coEvery { modelAccessDao.hasAccess(modelId, listOf(groupId), "execute") } returns true

        val result = authorizer.requireAccess(
            userId = userId,
            resourceId = modelId,
            accessMode = customMode
        )

        assertTrue(result.isRight())
    }
}
