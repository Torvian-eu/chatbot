package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.UserPreferenceApi
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the turn-notification preference plumbing of [DefaultUserPreferenceRepository]: the key
 * is read on sync, an undecodable value means "never configured", and a write stores the canonical
 * JSON under the global scope.
 */
class DefaultUserPreferenceRepositoryTurnNotificationTest {

    private lateinit var api: UserPreferenceApi
    private lateinit var repository: DefaultUserPreferenceRepository

    @BeforeTest
    fun setup() {
        api = mockk()
        repository = DefaultUserPreferenceRepository(api)
        coEvery { api.getDetailedPreferences() } returns Either.Right(emptyMap())
    }

    @Test
    fun `sync reads the stored toggles`() = runTest {
        coEvery { api.getPreferences() } returns Either.Right(
            mapOf(
                PreferenceKeys.TURN_NOTIFICATIONS to
                    """{"enabled":true,"soundEnabled":false,"osNotificationEnabled":false}"""
            )
        )

        val result = repository.syncPreferences()

        assertTrue(result.isRight())
        assertEquals(
            TurnNotificationPreference(enabled = true, soundEnabled = false, osNotificationEnabled = false),
            repository.turnNotificationPreference.value
        )
    }

    @Test
    fun `sync treats an undecodable value as absent`() = runTest {
        coEvery { api.getPreferences() } returns Either.Right(
            mapOf(PreferenceKeys.TURN_NOTIFICATIONS to "not-json")
        )

        repository.syncPreferences()

        assertNull(repository.turnNotificationPreference.value)
    }

    @Test
    fun `sync leaves the toggles unset when the key is missing`() = runTest {
        coEvery { api.getPreferences() } returns Either.Right(emptyMap())

        repository.syncPreferences()

        assertNull(repository.turnNotificationPreference.value)
    }

    @Test
    fun `setter stores canonical JSON in the global scope and re-syncs`() = runTest {
        val dtoSlot = slot<UserPreferenceDTO>()
        coEvery { api.updatePreference(PreferenceKeys.TURN_NOTIFICATIONS, capture(dtoSlot)) } returns Either.Right(Unit)
        coEvery { api.getPreferences() } returns Either.Right(
            mapOf(PreferenceKeys.TURN_NOTIFICATIONS to """{"enabled":true,"soundEnabled":true,"osNotificationEnabled":true}""")
        )

        val result = repository.setTurnNotificationPreference(
            TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = true)
        )

        assertTrue(result.isRight())
        assertEquals(PreferenceKeys.TURN_NOTIFICATIONS, dtoSlot.captured.key)
        assertEquals(PreferenceScope.GLOBAL, dtoSlot.captured.scope)
        assertEquals(
            """{"enabled":true,"soundEnabled":true,"osNotificationEnabled":true}""",
            dtoSlot.captured.value
        )
        assertEquals(TurnNotificationPreference.DEFAULT, repository.turnNotificationPreference.value)
    }

    @Test
    fun `setter failure is reported as a repository error`() = runTest {
        coEvery { api.updatePreference(any(), any()) } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.setTurnNotificationPreference(TurnNotificationPreference.DEFAULT)

        assertTrue(result.isLeft())
    }
}
