package eu.torvian.chatbot.app.service.turnnotification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TurnAlertSoundPlayerDesktop]'s best-effort contract: unusable or unreadable sound data
 * must never throw into the alert path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnAlertSoundPlayerDesktopTest {

    @Test
    fun `invalid audio data never throws`() = runTest {
        val player = TurnAlertSoundPlayerDesktop(loadSoundBytes = { byteArrayOf(1, 2, 3, 4) })

        player.playNotificationSound()
    }

    @Test
    fun `empty audio data never throws`() = runTest {
        val player = TurnAlertSoundPlayerDesktop(loadSoundBytes = { ByteArray(0) })

        player.playNotificationSound()
    }

    @Test
    fun `a failing resource read never throws`() = runTest {
        val player = TurnAlertSoundPlayerDesktop(loadSoundBytes = { error("resource missing") })

        player.playNotificationSound()
    }

    @Test
    fun `sound bytes are read only once`() = runTest {
        var loads = 0
        val player = TurnAlertSoundPlayerDesktop(
            loadSoundBytes = {
                loads++
                byteArrayOf(0, 0, 0, 0)
            }
        )

        player.playNotificationSound()
        player.playNotificationSound()

        // Caching keeps repeated alerts free of classpath reads, matching the documented contract.
        assertEquals(1, loads)
    }
}
