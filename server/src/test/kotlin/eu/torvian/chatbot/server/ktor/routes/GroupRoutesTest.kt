package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.CreateGroupRequest
import eu.torvian.chatbot.common.models.RenameGroupRequest
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integration tests for Group API routes.
 *
 * This test suite verifies the HTTP endpoints for group management:
 * - GET /api/v1/groups - List all groups
 * - POST /api/v1/groups - Create a new group
 * - DELETE /api/v1/groups/{groupId} - Delete group by ID
 * - PUT /api/v1/groups/{groupId} - Rename group by ID
 *
 */
class GroupRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var groupTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager

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
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        groupTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureGroupRoutes(this)
            }
        )

        testDataManager = container.get()
        testDataManager.createTables(setOf(Table.CHAT_GROUPS, Table.CHAT_SESSIONS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // --- GET /api/v1/groups Tests ---

    @Test
    fun `GET groups should return list of groups successfully`() = groupTestApplication {
        // Arrange
        testDataManager.insertChatGroup(testGroup1)
        testDataManager.insertChatGroup(testGroup2)
        val expectedGroups = listOf(testGroup1, testGroup2)

        // Act & Assert
        val response = client.get(href(GroupResource()))
        assertEquals(HttpStatusCode.OK, response.status)
        val groups = response.body<List<ChatGroup>>()
        assertEquals(expectedGroups, groups)
    }

    // --- POST /api/v1/groups Tests ---

    @Test
    fun `POST groups should create a new group successfully`() = groupTestApplication {
        // Arrange
        val newGroupName = "New Test Group"
        val createRequest = CreateGroupRequest(name = newGroupName)

        // Act
        val response = client.post(href(GroupResource())) {
            contentType(ContentType.Application.Json)
            setBody<CreateGroupRequest>(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdGroup = response.body<ChatGroup>()
        assertEquals(newGroupName, createdGroup.name)

        // Verify the group was actually created in the database
        val retrievedGroup = testDataManager.getChatGroup(createdGroup.id)
        assert(retrievedGroup != null && retrievedGroup.name == newGroupName)
    }

    @Test
    fun `POST groups should return 400 for blank name`() = groupTestApplication {
        // Arrange
        val blankName = "   "
        val createRequest = CreateGroupRequest(name = blankName)

        // Act
        val response = client.post(href(GroupResource())) {
            contentType(ContentType.Application.Json)
            setBody<CreateGroupRequest>(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid group name", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Group name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `POST groups should return 400 for empty name`() = groupTestApplication {
        // Arrange
        val emptyName = ""
        val createRequest = CreateGroupRequest(name = emptyName)

        // Act
        val response = client.post(href(GroupResource())) {
            contentType(ContentType.Application.Json)
            setBody<CreateGroupRequest>(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid group name", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Group name cannot be blank.", error.details?.get("reason"))
    }

    // --- DELETE /api/v1/groups/{groupId} Tests ---

    @Test
    fun `DELETE group should remove the group successfully`() = groupTestApplication {
        // Arrange
        testDataManager.insertChatGroup(testGroup1)

        // Act
        val response = client.delete(href(GroupResource.ById(groupId = testGroup1.id)))

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the group was actually deleted
        val retrievedGroup = testDataManager.getChatGroup(testGroup1.id)
        assert(retrievedGroup == null)
    }

    @Test
    fun `DELETE group with non-existent ID should return 404`() = groupTestApplication {
        // Act
        val nonExistentId = 999L
        val response = client.delete(href(GroupResource.ById(groupId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Group not found", error.message)
        assert(error.details?.containsKey("groupId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("groupId"))
    }

    // --- PUT /api/v1/groups/{groupId} Tests ---

    @Test
    fun `PUT group should rename the group successfully`() = groupTestApplication {
        // Arrange
        testDataManager.insertChatGroup(testGroup1)
        val newName = "Renamed Group"
        val renameRequest = RenameGroupRequest(name = newName)

        // Act
        val response = client.put(href(GroupResource.ById(groupId = testGroup1.id))) {
            contentType(ContentType.Application.Json)
            setBody<RenameGroupRequest>(renameRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the group was actually renamed in the database
        val retrievedGroup = testDataManager.getChatGroup(testGroup1.id)
        assert(retrievedGroup != null && retrievedGroup.name == newName)
    }

    @Test
    fun `PUT group with non-existent ID should return 404`() = groupTestApplication {
        // Arrange
        val nonExistentId = 999L
        val renameRequest = RenameGroupRequest(name = "New Name")

        // Act
        val response = client.put(href(GroupResource.ById(groupId = nonExistentId))) {
            contentType(ContentType.Application.Json)
            setBody<RenameGroupRequest>(renameRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Group not found", error.message)
        assert(error.details?.containsKey("groupId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("groupId"))
    }

    @Test
    fun `PUT group should return 400 for blank name`() = groupTestApplication {
        // Arrange
        testDataManager.insertChatGroup(testGroup1)
        val blankName = "   "
        val renameRequest = RenameGroupRequest(name = blankName)

        // Act
        val response = client.put(href(GroupResource.ById(groupId = testGroup1.id))) {
            contentType(ContentType.Application.Json)
            setBody<RenameGroupRequest>(renameRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid group name", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("New group name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `PUT group should return 400 for empty name`() = groupTestApplication {
        // Arrange
        testDataManager.insertChatGroup(testGroup1)
        val emptyName = ""
        val renameRequest = RenameGroupRequest(name = emptyName)

        // Act
        val response = client.put(href(GroupResource.ById(groupId = testGroup1.id))) {
            contentType(ContentType.Application.Json)
            setBody<RenameGroupRequest>(renameRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid group name", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("New group name cannot be blank.", error.details?.get("reason"))
    }
}
