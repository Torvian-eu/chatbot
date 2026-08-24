package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixService
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolNamePrefixError
import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ServerBuiltInToolNamePrefixServiceImpl].
 *
 * Integration coverage (real container + DB): prefix updates persist the global preference row and
 * rename the user's public tool names, blank means no prefix, invalid prefixes are rejected before
 * any persistence, and DELETE resets names to the hardcoded default. Mockk coverage: a rename
 * failure surfaces as [UpdateServerBuiltInToolNamePrefixError.RenameFailed] (the transaction
 * boundary turns the Either.Left into a rollback).
 */
class ServerBuiltInToolNamePrefixServiceTest {

    private lateinit var container: DIContainer
    private lateinit var service: ServerBuiltInToolNamePrefixService
    private lateinit var seeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao
    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        service = container.get()
        seeder = container.get()
        serverBuiltInToolDefinitionDao = container.get()
        userPreferenceDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.TOOL_DEFINITIONS,
                Table.SERVER_BUILTIN_TOOL_DEFINITIONS
            )
        )

        testDataManager.setup(
            TestDataSet(
                users = listOf(TestDefaults.user1)
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
    fun `updatePrefix persists the global row and renames public names`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        val before = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        assertTrue(before.all { it.name == "chatbot-${it.builtInToolName}" })

        val result = service.updatePrefix(TestDefaults.user1.id, "acme-")

        assertTrue(result.isRight(), "update failed: ${result.leftOrNull()}")
        assertEquals("acme-", storedPrefix(TestDefaults.user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        after.forEach { tool ->
            assertEquals("acme-${tool.builtInToolName}", tool.name)
            assertEquals(before.first { it.id == tool.id }.builtInToolName, tool.builtInToolName)
        }
    }

    @Test
    fun `updatePrefix with blank value produces canonical names`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        service.updatePrefix(TestDefaults.user1.id, "acme-")

        val result = service.updatePrefix(TestDefaults.user1.id, "")

        assertTrue(result.isRight(), "update failed: ${result.leftOrNull()}")
        assertEquals("", storedPrefix(TestDefaults.user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        after.forEach { tool ->
            assertEquals(tool.builtInToolName, tool.name)
        }
    }

    @Test
    fun `updatePrefix rejects an invalid prefix and persists nothing`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        val before = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)

        val result = service.updatePrefix(TestDefaults.user1.id, "bad.prefix")

        assertTrue(result.isLeft())
        assertIs<UpdateServerBuiltInToolNamePrefixError.InvalidInput>(result.leftOrNull())
        assertEquals(null, storedPrefix(TestDefaults.user1.id))
        // Public names are untouched by the rejected write.
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        assertEquals(before.map { it.name }.toSet(), after.map { it.name }.toSet())
    }

    @Test
    fun `deletePrefix removes the row and resets names to the default prefix`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        service.updatePrefix(TestDefaults.user1.id, "acme-")
        assertEquals("acme-", storedPrefix(TestDefaults.user1.id))

        val result = service.deletePrefix(TestDefaults.user1.id)

        assertTrue(result.isRight(), "delete failed: ${result.leftOrNull()}")
        assertEquals(null, storedPrefix(TestDefaults.user1.id))
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        after.forEach { tool ->
            assertEquals(
                ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX + tool.builtInToolName,
                tool.name
            )
        }
    }

    @Test
    fun `deletePrefix is idempotent when no preference row exists`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)

        val result = service.deletePrefix(TestDefaults.user1.id)

        assertTrue(result.isRight(), "delete failed: ${result.leftOrNull()}")
        assertEquals(null, storedPrefix(TestDefaults.user1.id))
        // Names stay at the default; no rename was needed.
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        after.forEach { tool ->
            assertEquals("chatbot-${tool.builtInToolName}", tool.name)
        }
    }

    // --- Mockk-based rollback-shape tests (the transaction scope turns Either.Left into rollback) ---

    private val transactionScope = mockk<TransactionScope>()
    private val mockUserPreferenceDao = mockk<UserPreferenceDao>()
    private val mockSeeder = mockk<ServerBuiltInToolDefinitionSeeder>()

    /**
     * Makes the mocked [TransactionScope] execute the block directly, mirroring the re-entrant
     * behavior of [eu.torvian.chatbot.server.utils.transactions.ExposedTransactionScope] when a
     * transaction is already active (the service is the transaction boundary in production).
     */
    private fun runInMockTransaction() {
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val inner = firstArg<suspend () -> Any>()
            inner()
        }
    }

    @Test
    fun `updatePrefix surfaces RenameFailed when the rename fails`() = runTest {
        val resolver = mockk<ServerBuiltInToolNamePrefixResolver>()
        val failingService = ServerBuiltInToolNamePrefixServiceImpl(
            userPreferenceDao = mockUserPreferenceDao,
            serverBuiltInToolDefinitionSeeder = mockSeeder,
            prefixResolver = resolver,
            transactionScope = transactionScope
        )
        runInMockTransaction()
        coEvery { mockUserPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) } returns Unit
        coEvery { mockSeeder.renamePublicNamesForPrefix(1L, "acme-") } returns
                SeedServerBuiltInToolsError.ToolCreationFailed(
                    eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError.InvalidName("boom", "x")
                ).left()

        val result = failingService.updatePrefix(1L, "acme-")

        assertTrue(result.isLeft())
        assertIs<UpdateServerBuiltInToolNamePrefixError.RenameFailed>(result.leftOrNull())
        // The preference write happened before the rename attempt; the transaction boundary rolls
        // it back when the block returns Either.Left (covered by ExposedTransactionScope behavior).
        coVerify(exactly = 1) {
            mockUserPreferenceDao.upsertPreference(
                1L,
                null,
                null,
                PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                "acme-"
            )
        }
        coVerify(exactly = 1) { mockSeeder.renamePublicNamesForPrefix(1L, "acme-") }
    }

    @Test
    fun `deletePrefix surfaces RenameFailed when the rename fails`() = runTest {
        val resolver = mockk<ServerBuiltInToolNamePrefixResolver>()
        val failingService = ServerBuiltInToolNamePrefixServiceImpl(
            userPreferenceDao = mockUserPreferenceDao,
            serverBuiltInToolDefinitionSeeder = mockSeeder,
            prefixResolver = resolver,
            transactionScope = transactionScope
        )
        runInMockTransaction()
        coEvery {
            mockUserPreferenceDao.deletePreference(
                1L,
                null,
                PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX
            )
        } returns Unit
        coEvery { resolver.resolvePrefix(1L) } returns ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX
        coEvery {
            mockSeeder.renamePublicNamesForPrefix(
                1L,
                ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX
            )
        } returns
                SeedServerBuiltInToolsError.ToolCreationFailed(
                    eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError.InvalidName("boom", "x")
                ).left()

        val result = failingService.deletePrefix(1L)

        assertTrue(result.isLeft())
        assertIs<UpdateServerBuiltInToolNamePrefixError.RenameFailed>(result.leftOrNull())
        coVerify(exactly = 1) {
            mockUserPreferenceDao.deletePreference(
                1L,
                null,
                PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX
            )
        }
        coVerify(exactly = 1) {
            mockSeeder.renamePublicNamesForPrefix(
                1L,
                ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX
            )
        }
    }

    // --- Real-DB rollback coverage (F4): a rename failure inside a real ExposedTransactionScope ---

    @Test
    fun `updatePrefix rolls back the preference row when the rename fails inside a real transaction`() = runTest {
        // Seed real rows first so the rename step actually has something to rename.
        seeder.ensureForUser(TestDefaults.user1.id)

        // Spy on the real DAO and force only the rename step to fail (the linkage guard returns
        // NotFound), while every other call (e.g. getToolsByUserId) still hits the real database.
        val realDao = serverBuiltInToolDefinitionDao
        val daoSpy = spyk(realDao)
        coEvery { daoSpy.updatePublicName(any(), any()) } returns
                ServerBuiltInToolDefinitionError.NotFound(toolDefinitionId = 0L).left()

        // Build the service with a real ExposedTransactionScope and a seeder whose DAO fails on
        // rename: the preference upsert and the rename attempt run in one real transaction, and the
        // scope must roll the preference row back when the block returns Either.Left.
        val failingSeeder = ServerBuiltInToolDefinitionSeeder(
            serverBuiltInToolDefinitionDao = daoSpy,
            toolService = container.get(),
            transactionScope = container.get(),
            prefixResolver = container.get()
        )
        val realService = ServerBuiltInToolNamePrefixServiceImpl(
            userPreferenceDao = userPreferenceDao,
            serverBuiltInToolDefinitionSeeder = failingSeeder,
            prefixResolver = container.get(),
            transactionScope = container.get()
        )

        val result = realService.updatePrefix(TestDefaults.user1.id, "acme-")

        assertTrue(result.isLeft())
        assertIs<UpdateServerBuiltInToolNamePrefixError.RenameFailed>(result.leftOrNull())
        // The preference row was written inside the transaction and rolled back on the rename
        // failure: it must be absent after the failed update.
        assertNull(storedPrefix(TestDefaults.user1.id))
        // Public names are unchanged because the rename never committed.
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        after.forEach { tool ->
            assertEquals("chatbot-${tool.builtInToolName}", tool.name)
        }
    }
}
