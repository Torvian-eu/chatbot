package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the shared OS-channel classification, which both the alert dispatcher and the settings
 * surface act on.
 */
class TurnOsChannelStateTest {

    /**
     * Builds toggles for the OS channel.
     *
     * @param osNotificationEnabled Stored OS-channel toggle.
     * @return Toggles under test.
     */
    private fun toggles(osNotificationEnabled: Boolean) = TurnNotificationPreference(
        enabled = true,
        soundEnabled = true,
        osNotificationEnabled = osNotificationEnabled
    )

    @Test
    fun `a granted channel reads as active`() {
        assertEquals(
            TurnOsChannelState.ACTIVE,
            toggles(osNotificationEnabled = true).osChannelState(TurnOsNotificationPermission.GRANTED)
        )
    }

    @Test
    fun `an undecided permission asks to be requested`() {
        assertEquals(
            TurnOsChannelState.NEEDS_PERMISSION,
            toggles(osNotificationEnabled = true).osChannelState(TurnOsNotificationPermission.NOT_DETERMINED)
        )
    }

    @Test
    fun `a refused permission reads as blocked`() {
        assertEquals(
            TurnOsChannelState.BLOCKED,
            toggles(osNotificationEnabled = true).osChannelState(TurnOsNotificationPermission.DENIED)
        )
    }

    @Test
    fun `a refusal that cannot be prompted again reads as blocked by the settings`() {
        assertEquals(
            TurnOsChannelState.BLOCKED_BY_SETTINGS,
            toggles(osNotificationEnabled = true).osChannelState(TurnOsNotificationPermission.REQUIRES_SETTINGS)
        )
    }

    @Test
    fun `a platform without notifications reads as unsupported`() {
        assertEquals(
            TurnOsChannelState.UNSUPPORTED,
            toggles(osNotificationEnabled = true).osChannelState(TurnOsNotificationPermission.UNSUPPORTED)
        )
    }

    @Test
    fun `a channel the user turned off stays disabled whatever the permission says`() {
        val disabled = toggles(osNotificationEnabled = false)
        TurnOsNotificationPermission.entries.forEach { permission ->
            assertEquals(TurnOsChannelState.DISABLED, disabled.osChannelState(permission))
        }
    }

    @Test
    fun `only a missing or still promptable refusal can be requested`() {
        val requestable = setOf(
            TurnOsNotificationPermission.NOT_DETERMINED,
            TurnOsNotificationPermission.DENIED
        )
        TurnOsNotificationPermission.entries.forEach { permission ->
            val state = toggles(osNotificationEnabled = true).osChannelState(permission)
            assertEquals(
                permission in requestable,
                state.canBeRequested,
                "unexpected requestability for $state"
            )
        }
        assertFalse(TurnOsChannelState.DISABLED.canBeRequested)
        assertFalse(TurnOsChannelState.BLOCKED_BY_SETTINGS.canBeRequested)
        assertTrue(TurnOsChannelState.NEEDS_PERMISSION.canBeRequested)
    }
}
