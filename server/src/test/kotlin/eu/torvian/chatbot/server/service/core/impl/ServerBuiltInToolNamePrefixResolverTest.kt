package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Tests for [ServerBuiltInToolNamePrefixResolverImpl].
 *
 * Covers the resolution rules: absent preference → the fallback default (`"chatbot-"`), stored
 * value → returned verbatim, blank stored value → `""` (no prefix), and the constructor seam that
 * lets a future configurable server default replace the hardcoded constant.
 */
class ServerBuiltInToolNamePrefixResolverTest {

    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var resolver: ServerBuiltInToolNamePrefixResolverImpl

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val userId = 7L

    private fun preferenceRow(value: String): UserPreferenceEntity = UserPreferenceEntity(
        id = 1L,
        userId = userId,
        deviceId = null,
        scopeId = "GLOBAL",
        prefKey = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
        prefValue = value,
        updatedAt = now
    )

    @BeforeEach
    fun setUp() {
        userPreferenceDao = mockk()
        resolver = ServerBuiltInToolNamePrefixResolverImpl(userPreferenceDao)
    }

    @Test
    fun `no preference row resolves to the hardcoded default prefix`() = runTest {
        coEvery { userPreferenceDao.getPreferencesForUser(userId, null) } returns emptyList()

        assertEquals(
            ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX,
            resolver.resolvePrefix(userId)
        )
    }

    @Test
    fun `stored value is returned verbatim`() = runTest {
        coEvery { userPreferenceDao.getPreferencesForUser(userId, null) } returns
            listOf(preferenceRow("acme-"))

        assertEquals("acme-", resolver.resolvePrefix(userId))
    }

    @Test
    fun `blank stored value resolves to no prefix`() = runTest {
        coEvery { userPreferenceDao.getPreferencesForUser(userId, null) } returns
            listOf(preferenceRow(""))

        assertEquals("", resolver.resolvePrefix(userId))
    }

    @Test
    fun `custom default parameter is respected (future config seam)`() = runTest {
        val customResolver = ServerBuiltInToolNamePrefixResolverImpl(userPreferenceDao, defaultPrefix = "corp-")
        coEvery { userPreferenceDao.getPreferencesForUser(userId, null) } returns emptyList()

        assertEquals("corp-", customResolver.resolvePrefix(userId))
    }

    @Test
    fun `other preference keys do not influence the resolution`() = runTest {
        val unrelated = UserPreferenceEntity(
            id = 2L,
            userId = userId,
            deviceId = null,
            scopeId = "GLOBAL",
            prefKey = "current_theme",
            prefValue = "dark",
            updatedAt = now
        )
        coEvery { userPreferenceDao.getPreferencesForUser(userId, null) } returns listOf(unrelated)

        assertEquals(
            ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX,
            resolver.resolvePrefix(userId)
        )
    }
}
