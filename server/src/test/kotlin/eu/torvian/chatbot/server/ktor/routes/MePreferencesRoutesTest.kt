package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.MeResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.impl.ServerBuiltInToolDefinitionSeeder
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the `/api/v1/me/preferences/{key}` routes, focusing on the well-known
 * `server_builtin_tool_name_prefix` branch.
 *
 * Covers: GLOBAL-scope prefix writes that persist the preference and rename the user's tool names
 * atomically, rejection of invalid prefixes and DEVICE scope, DELETE resetting names to the server
 * default, and the guarantee that non-prefix keys still use the generic preference path.
 */
class MePreferencesRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var seeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao
    private lateinit var userPreferenceDao: UserPreferenceDao

    private val user1 = TestDefaults.user1

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureMeRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        seeder = container.get()
        serverBuiltInToolDefinitionDao = container.get()
        userPreferenceDao = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.TOOL_DEFINITIONS,
                Table.SERVER_BUILTIN_TOOL_DEFINITIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    private suspend fun storedPrefix(userId: Long): String? =
        userPreferenceDao.getPreferencesForUser(userId, null)
            .firstOrNull { it.prefKey == PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX }
            ?.prefValue

    @Test
    fun `PUT prefix with GLOBAL scope persists the preference and renames the user's tools`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val before = seeder.ensureForUser(user1.id).getOrNull()!!
        assertTrue(before.all { it.name == "chatbot-${it.builtInToolName}" })

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                    value = "acme-",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("acme-", storedPrefix(user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(user1.id)
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        after.forEach { tool ->
            assertEquals("acme-${tool.builtInToolName}", tool.name)
        }
    }

    @Test
    fun `PUT prefix with a body key that does not match the path returns 400 and persists nothing`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val before = seeder.ensureForUser(user1.id).getOrNull()!!

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = "some_other_key",
                    value = "acme-",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        // Nothing was persisted and the public names were not renamed.
        assertNull(storedPrefix(user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(user1.id)
        assertEquals(before.map { it.name }.toSet(), after.map { it.name }.toSet())
    }

    @Test
    fun `PUT prefix with an invalid prefix returns 400 and persists nothing`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val before = seeder.ensureForUser(user1.id).getOrNull()!!

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                    value = "bad.prefix",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertNull(storedPrefix(user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(user1.id)
        assertEquals(before.map { it.name }.toSet(), after.map { it.name }.toSet())
    }

    @Test
    fun `PUT prefix with DEVICE scope returns 400`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        seeder.ensureForUser(user1.id)

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                    value = "acme-",
                    scope = PreferenceScope.DEVICE
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertNull(storedPrefix(user1.id))
    }

    @Test
    fun `DELETE prefix resets the global row and renames the user's tools to the default`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        seeder.ensureForUser(user1.id)
        // Set a custom prefix first.
        client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                    value = "acme-",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }
        assertEquals("acme-", storedPrefix(user1.id))

        val response = client.delete(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(storedPrefix(user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(user1.id)
        after.forEach { tool ->
            assertEquals("chatbot-${tool.builtInToolName}", tool.name)
        }
    }

    @Test
    fun `non-prefix keys still use the generic preference path`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val putResponse = client.put(href(MeResource.Preferences.ByKey(key = "current_theme"))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = "current_theme",
                    value = "dark",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }
        assertEquals(HttpStatusCode.NoContent, putResponse.status)
        assertEquals(
            "dark",
            userPreferenceDao.getPreferencesForUser(user1.id, null)
                .firstOrNull { it.prefKey == "current_theme" }?.prefValue
        )

        val deleteResponse = client.delete(href(MeResource.Preferences.ByKey(key = "current_theme", scope = PreferenceScope.GLOBAL))) {
            authenticate(token)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)
        assertNull(
            userPreferenceDao.getPreferencesForUser(user1.id, null)
                .firstOrNull { it.prefKey == "current_theme" }
        )
    }
}
