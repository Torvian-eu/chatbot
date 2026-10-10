package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the `conversation_compaction` preference write/delete path.
 *
 * Covers structural validation (including the requirement that an enabled preference names both
 * auxiliary references), the 100,000-token default for an omitted threshold, the explicit write-time
 * checks for the non-runtime concerns only (READ access to the referenced model/settings, settings
 * existence and model pairing — never provider, model activity, strategy, or credentials), canonical
 * JSON persistence (including the `enabled` flag), the disabled preference that may omit its
 * references, and GLOBAL-scope deletion.
 */
class CompactionPreferenceServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userPreferenceDao = mockk<UserPreferenceDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val modelSettingsService = mockk<ModelSettingsService>()

    /** Transactions execute the block directly; DAO writes are mocked. */
    private object PassthroughTransactionScope : TransactionScope {
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override suspend fun <T> execute(block: suspend () -> T): T = block()
    }

    /**
     * Builds the service under test with all collaborators mocked.
     */
    private fun service() = DefaultCompactionPreferenceService(
        json = json,
        userPreferenceDao = userPreferenceDao,
        authorizationService = authorizationService,
        modelSettingsService = modelSettingsService,
        transactionScope = PassthroughTransactionScope
    )

    private val validPreference = ConversationCompactionPreference(
        modelId = 1L,
        settingsId = 2L,
        instruction = "Summarize faithfully",
        thresholdTokens = 50_000L
    )

    private val settings = ChatModelSettings(id = 2L, modelId = 1L, name = "Default", stream = false)

    /**
     * Stubs the write-time non-runtime checks for the fixture preference: READ access to the model
     * and settings, and a settings profile that exists and belongs to the referenced model.
     */
    private fun stubValidConfiguration() {
        coEvery { authorizationService.requireAccess(1L, ResourceType.MODEL, 1L, AccessMode.READ) } returns Unit.right()
        coEvery { authorizationService.requireAccess(1L, ResourceType.SETTINGS, 2L, AccessMode.READ) } returns Unit.right()
        coEvery { modelSettingsService.getSettingsById(2L) } returns settings.right()
    }

    @Test
    fun `valid configuration is validated and stored as canonical JSON`() = runTest {
        stubValidConfiguration()
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertTrue(result.isRight(), "Expected successful update: $result")

        // The non-runtime checks ran explicitly (model/settings access + settings pairing) before
        // storage; the runtime resolver was not consulted.
        coVerify(exactly = 1) { authorizationService.requireAccess(1L, ResourceType.MODEL, 1L, AccessMode.READ) }
        coVerify(exactly = 1) { authorizationService.requireAccess(1L, ResourceType.SETTINGS, 2L, AccessMode.READ) }
        coVerify(exactly = 1) { modelSettingsService.getSettingsById(2L) }
        coVerify(exactly = 1) {
            userPreferenceDao.upsertPreference(
                userId = 1L,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION,
                value = json.encodeToString(validPreference)
            )
        }
    }

    @Test
    fun `omitted threshold defaults to 100000 tokens and is stored canonically`() = runTest {
        val raw = """{"modelId":1,"settingsId":2,"instruction":"Summarize"}"""
        val decoded = ConversationCompactionPreference(
            modelId = 1L,
            settingsId = 2L,
            instruction = "Summarize",
            thresholdTokens = 100_000L
        )
        stubValidConfiguration()
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, raw)
        assertTrue(result.isRight())
        coVerify(exactly = 1) {
            userPreferenceDao.upsertPreference(
                1L,
                null,
                null,
                PreferenceKeys.CONVERSATION_COMPACTION,
                json.encodeToString(decoded)
            )
        }
    }

    @Test
    fun `disabled preference round-trips through validation and is stored canonically`() = runTest {
        // `automaticCompactionEnabled = false` is a stored configuration, not a bypass: it must still
        // decode, validate, and persist like any other preference, and it disables automatic
        // compaction only at runtime.
        val disabled = validPreference.copy(automaticCompactionEnabled = false)
        stubValidConfiguration()
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, json.encodeToString(disabled))
        assertTrue(result.isRight(), "Expected successful update: $result")

        coVerify(exactly = 1) {
            userPreferenceDao.upsertPreference(
                userId = 1L,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION,
                value = json.encodeToString(disabled)
            )
        }
    }

    @Test
    fun `an enabled preference without model and settings references is rejected and nothing is stored`() = runTest {
        // An enabled preference must name the compactor it would run: null references are legitimate only
        // while compaction is disabled, so the write path refuses to store a state that would reject
        // every turn of a compaction-enabled session.
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val incomplete = validPreference.copy(modelId = null, settingsId = null)
        val result = service().updateConfiguration(1L, json.encodeToString(incomplete))

        assertIs<CompactionPreferenceError.InvalidValue>(result.leftOrNull())
        coVerify(exactly = 0) { authorizationService.requireAccess(any(), any(), any(), any()) }
        coVerify(exactly = 0) { modelSettingsService.getSettingsById(any()) }
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an enabled preference that omits the enabled flag is rejected for null references too`() = runTest {
        // `enabled` defaults to true when the request omits it, so such a payload is an enabled
        // preference and has to carry both references.
        val raw = """{"modelId":null,"settingsId":null,"instruction":"Summarize","thresholdTokens":50000}"""

        val result = service().updateConfiguration(1L, raw)

        assertIs<CompactionPreferenceError.InvalidValue>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a disabled preference is stored without references and without non-runtime checks`() = runTest {
        // `automaticCompactionEnabled = false` is the legal way to keep a preference whose model/settings
        // rows are gone: a preference with automatic compaction disabled never resolves a compactor,
        // so the access/correctness checks are skipped and the value is stored as-is.
        val disabledWithoutIds =
            validPreference.copy(modelId = null, settingsId = null, automaticCompactionEnabled = false)
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, json.encodeToString(disabledWithoutIds))

        assertTrue(result.isRight(), "Expected successful update: $result")
        coVerify(exactly = 0) { authorizationService.requireAccess(any(), any(), any(), any()) }
        coVerify(exactly = 0) { modelSettingsService.getSettingsById(any()) }
        coVerify(exactly = 1) {
            userPreferenceDao.upsertPreference(
                userId = 1L,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION,
                value = json.encodeToString(disabledWithoutIds)
            )
        }
    }

    @Test
    fun `malformed JSON is rejected as an invalid value and nothing is stored`() = runTest {
        val result = service().updateConfiguration(1L, "not-json{")
        assertIs<CompactionPreferenceError.InvalidValue>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `non-positive ids blank instruction and non-positive threshold are rejected`() = runTest {
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val badModel = validPreference.copy(modelId = 0L)
        assertIs<CompactionPreferenceError.InvalidValue>(
            service().updateConfiguration(1L, json.encodeToString(badModel)).leftOrNull()
        )

        val badSettings = validPreference.copy(settingsId = -1L)
        assertIs<CompactionPreferenceError.InvalidValue>(
            service().updateConfiguration(1L, json.encodeToString(badSettings)).leftOrNull()
        )

        val blankInstruction = validPreference.copy(instruction = "   ")
        assertIs<CompactionPreferenceError.InvalidValue>(
            service().updateConfiguration(1L, json.encodeToString(blankInstruction)).leftOrNull()
        )

        val badThreshold = validPreference.copy(thresholdTokens = 0L)
        assertIs<CompactionPreferenceError.InvalidValue>(
            service().updateConfiguration(1L, json.encodeToString(badThreshold)).leftOrNull()
        )

        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `denied model access is rejected as an access-denied error`() = runTest {
        coEvery {
            authorizationService.requireAccess(1L, ResourceType.MODEL, 1L, AccessMode.READ)
        } returns ResourceAuthorizationError.AccessDenied(
            userId = 1L,
            resourceType = ResourceType.MODEL,
            id = 1L,
            accessMode = AccessMode.READ
        ).left()

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertIs<CompactionPreferenceError.AccessDenied>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `denied settings access is rejected as an access-denied error`() = runTest {
        coEvery {
            authorizationService.requireAccess(1L, ResourceType.MODEL, 1L, AccessMode.READ)
        } returns Unit.right()
        coEvery {
            authorizationService.requireAccess(1L, ResourceType.SETTINGS, 2L, AccessMode.READ)
        } returns ResourceAuthorizationError.AccessDenied(
            userId = 1L,
            resourceType = ResourceType.SETTINGS,
            id = 2L,
            accessMode = AccessMode.READ
        ).left()

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertIs<CompactionPreferenceError.AccessDenied>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `settings that no longer exist are rejected as a not-found error`() = runTest {
        coEvery {
            authorizationService.requireAccess(1L, ResourceType.MODEL, 1L, AccessMode.READ)
        } returns Unit.right()
        coEvery {
            authorizationService.requireAccess(1L, ResourceType.SETTINGS, 2L, AccessMode.READ)
        } returns Unit.right()
        coEvery { modelSettingsService.getSettingsById(2L) } returns
            GetSettingsByIdError.SettingsNotFound(2L).left()

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertIs<CompactionPreferenceError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `streaming settings are rejected as an incompatible configuration`() = runTest {
        stubValidConfiguration()
        // The chat-like/non-streaming profile check is a static write-time concern: a streaming
        // settings profile cannot drive the non-streaming auxiliary call, so the PUT must fail.
        coEvery { modelSettingsService.getSettingsById(2L) } returns settings.copy(stream = true).right()
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertIs<CompactionPreferenceError.IncompatibleConfiguration>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `settings belonging to a different model are rejected as an incompatible configuration`() = runTest {
        stubValidConfiguration()
        // Override the settings lookup with a profile owned by a different model, so the pairing
        // check (non-runtime correctness) fails before storage.
        coEvery { modelSettingsService.getSettingsById(2L) } returns settings.copy(modelId = 99L).right()
        coEvery { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit

        val result = service().updateConfiguration(1L, json.encodeToString(validPreference))
        assertIs<CompactionPreferenceError.IncompatibleConfiguration>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete removes only the global row`() = runTest {
        coEvery { userPreferenceDao.deletePreference(any(), any(), any()) } returns Unit
        val result = service().deleteConfiguration(1L)
        assertTrue(result.isRight())
        coVerify(exactly = 1) {
            userPreferenceDao.deletePreference(
                userId = 1L,
                internalDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION
            )
        }
    }
}