package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.MeResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
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
import kotlinx.serialization.json.Json
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

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
                Table.SERVER_BUILTIN_TOOL_DEFINITIONS,
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.MODEL_SETTINGS,
                Table.LLM_PROVIDER_OWNERS,
                Table.LLM_MODEL_OWNERS,
                Table.MODEL_SETTINGS_OWNERS,
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS
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

        val response =
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

        val response =
            client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
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

        val response =
            client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
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

        val response =
            client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
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

        val response =
            client.delete(href(MeResource.Preferences.ByKey(key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX))) {
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

        val deleteResponse =
            client.delete(href(MeResource.Preferences.ByKey(key = "current_theme", scope = PreferenceScope.GLOBAL))) {
                authenticate(token)
            }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)
        assertNull(
            userPreferenceDao.getPreferencesForUser(user1.id, null)
                .firstOrNull { it.prefKey == "current_theme" }
        )
    }

    @Test
    fun `PUT conversation_compaction with DEVICE scope returns 400`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.CONVERSATION_COMPACTION,
                    value = """{"modelId":1,"settingsId":1,"instruction":"Summarize"}""",
                    scope = PreferenceScope.DEVICE
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertNull(storedCompactionPreference(user1.id))
    }

    @Test
    fun `PUT conversation_compaction with malformed JSON returns 400`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.CONVERSATION_COMPACTION,
                    value = "not-json{",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertNull(storedCompactionPreference(user1.id))
    }

    @Test
    fun `PUT conversation_compaction with a body key that does not match the path returns 400`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = "some_other_key",
                    value = """{"modelId":1,"settingsId":1,"instruction":"Summarize"}""",
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertNull(storedCompactionPreference(user1.id))
    }

    @Test
    fun `PUT conversation_compaction with an accessible model and non-streaming settings stores the canonical value`() =
        app {
            val token = authHelper.createUserAndGetToken(user1)
            val provider = TestDefaults.llmProvider1.copy(apiKeyId = null)
            val model = TestDefaults.llmModel1.copy(providerId = provider.id)
            val settings = TestDefaults.modelSettings1.copy(modelId = model.id, stream = false)
            testDataManager.insertLLMProvider(provider)
            testDataManager.insertLLMModel(model)
            testDataManager.insertModelSettings(settings)
            testDataManager.insertProviderOwnership(provider.id, user1.id)
            testDataManager.insertModelOwnership(model.id, user1.id)
            testDataManager.insertSettingsOwnership(settings.id, user1.id)

            val value =
                """{"modelId":${model.id},"settingsId":${settings.id},"instruction":"Summarize faithfully","thresholdTokens":50000}"""
            val response =
                client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
                    authenticate(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        UserPreferenceDTO(
                            key = PreferenceKeys.CONVERSATION_COMPACTION,
                            value = value,
                            scope = PreferenceScope.GLOBAL
                        )
                    )
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            // The service stores the canonical re-encoding, which now materializes the defaulted
            // summaryLabel alongside the explicit fields, so raw round-trip equality no longer holds.
            val canonical = json.encodeToString(
                ConversationCompactionPreference.serializer(),
                ConversationCompactionPreference(
                    modelId = model.id,
                    settingsId = settings.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 50_000L
                )
            )
            assertEquals(canonical, storedCompactionPreference(user1.id))
        }

    @Test
    fun `PUT conversation_compaction without model and settings stores the inactive preference`() = app {
        // A preference whose model/settings rows no longer exist is stored as-is (no runtime
        // resolution), so the client can persist null ids and re-configure a valid pair later.
        val token = authHelper.createUserAndGetToken(user1)

        val value = """{"modelId":null,"settingsId":null,"instruction":"Summarize"}"""
        val response =
            client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
                authenticate(token)
                contentType(ContentType.Application.Json)
                setBody(
                    UserPreferenceDTO(
                        key = PreferenceKeys.CONVERSATION_COMPACTION,
                        value = value,
                        scope = PreferenceScope.GLOBAL
                    )
                )
            }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val canonical = json.encodeToString(
            ConversationCompactionPreference.serializer(),
            ConversationCompactionPreference(
                modelId = null,
                settingsId = null,
                instruction = "Summarize"
            )
        )
        assertEquals(canonical, storedCompactionPreference(user1.id))
    }

    @Test
    fun `PUT conversation_compaction with streaming settings is rejected as a model configuration error`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = null)
        val model = TestDefaults.llmModel1.copy(providerId = provider.id)
        val settings = TestDefaults.modelSettings1.copy(modelId = model.id, stream = true)
        testDataManager.insertLLMProvider(provider)
        testDataManager.insertLLMModel(model)
        testDataManager.insertModelSettings(settings)
        testDataManager.insertProviderOwnership(provider.id, user1.id)
        testDataManager.insertModelOwnership(model.id, user1.id)
        testDataManager.insertSettingsOwnership(settings.id, user1.id)

        val value = """{"modelId":${model.id},"settingsId":${settings.id},"instruction":"Summarize"}"""
        val response = client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.CONVERSATION_COMPACTION,
                    value = value,
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        // The chat-like/non-streaming profile check is a static write-time concern: a streaming
        // settings profile cannot drive the non-streaming auxiliary call, so the PUT is rejected.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR.code, error.code)
        assertNull(storedCompactionPreference(user1.id))
    }

    @Test
    fun `DELETE conversation_compaction removes only the global row`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = null)
        val model = TestDefaults.llmModel1.copy(providerId = provider.id)
        val settings = TestDefaults.modelSettings1.copy(modelId = model.id, stream = false)
        testDataManager.insertLLMProvider(provider)
        testDataManager.insertLLMModel(model)
        testDataManager.insertModelSettings(settings)
        testDataManager.insertProviderOwnership(provider.id, user1.id)
        testDataManager.insertModelOwnership(model.id, user1.id)
        testDataManager.insertSettingsOwnership(settings.id, user1.id)
        val rawValue = """{"modelId":${model.id},"settingsId":${settings.id},"instruction":"Summarize"}"""

        client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.CONVERSATION_COMPACTION,
                    value = rawValue,
                    scope = PreferenceScope.GLOBAL
                )
            )
        }
        // The service stores the canonical encoding, which materializes the default threshold.
        assertEquals(canonicalCompactionValue(model.id, settings.id), storedCompactionPreference(user1.id))

        val response = client.delete(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(storedCompactionPreference(user1.id))
    }

    @Test
    fun `GET conversation_compaction returns the stored raw string`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = null)
        val model = TestDefaults.llmModel1.copy(providerId = provider.id)
        val settings = TestDefaults.modelSettings1.copy(modelId = model.id, stream = false)
        testDataManager.insertLLMProvider(provider)
        testDataManager.insertLLMModel(model)
        testDataManager.insertModelSettings(settings)
        testDataManager.insertProviderOwnership(provider.id, user1.id)
        testDataManager.insertModelOwnership(model.id, user1.id)
        testDataManager.insertSettingsOwnership(settings.id, user1.id)
        val rawValue = """{"modelId":${model.id},"settingsId":${settings.id},"instruction":"Summarize"}"""

        client.put(href(MeResource.Preferences.ByKey(key = PreferenceKeys.CONVERSATION_COMPACTION))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                UserPreferenceDTO(
                    key = PreferenceKeys.CONVERSATION_COMPACTION,
                    value = rawValue,
                    scope = PreferenceScope.GLOBAL
                )
            )
        }

        val getResponse = client.get("/api/v1/me/preferences") {
            authenticate(token)
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val body = getResponse.body<Map<String, String>>()
        assertTrue(body.containsKey(PreferenceKeys.CONVERSATION_COMPACTION))
        assertEquals(canonicalCompactionValue(model.id, settings.id), body[PreferenceKeys.CONVERSATION_COMPACTION])
    }

    /**
     * Canonical JSON encoding of the default-threshold preference for the given model/settings ids.
     */
    private fun canonicalCompactionValue(modelId: Long, settingsId: Long): String = json.encodeToString(
        ConversationCompactionPreference.serializer(),
        ConversationCompactionPreference(
            modelId = modelId,
            settingsId = settingsId,
            instruction = "Summarize"
        )
    )

    /**
     * Reads the stored global conversation-compaction preference value for the user.
     */
    private suspend fun storedCompactionPreference(userId: Long): String? =
        userPreferenceDao.getPreferencesForUser(userId, null)
            .firstOrNull { it.prefKey == PreferenceKeys.CONVERSATION_COMPACTION }
            ?.prefValue
}
