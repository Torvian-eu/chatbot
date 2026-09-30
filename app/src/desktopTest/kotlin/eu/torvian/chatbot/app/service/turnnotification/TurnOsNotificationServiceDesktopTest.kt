package eu.torvian.chatbot.app.service.turnnotification

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TurnOsNotificationServiceDesktop]'s degradation path: when the platform cannot host a
 * tray icon, or the icon resource cannot be decoded, the service must report
 * [TurnOsNotificationPermission.UNSUPPORTED] and every call must stay a silent no-op rather than
 * throwing into the alert path.
 *
 * Tray availability and the icon bytes are supplied through the class's injectable seams, so the icon
 * failure is isolated from a machine that has no tray at all; a real tray and balloon rendering are
 * left to a smoke test.
 */
class TurnOsNotificationServiceDesktopTest {

    @Test
    fun `an undecodable tray icon degrades to unsupported`() = runTest {
        val service = TurnOsNotificationServiceDesktop(
            // Claimed to be supported so the undecodable bytes, not the platform, cause the degradation.
            isTraySupported = { true },
            loadIconBytes = { byteArrayOf(1, 2, 3, 4) }
        )

        assertEquals(TurnOsNotificationPermission.UNSUPPORTED, service.permissionState())

        // Showing and raising must remain best effort once the channel is unavailable.
        service.showNotification(TurnOsNotificationRequest(1L, "title", "body"))
        service.bringAppToFront()
    }

    @Test
    fun `empty tray icon bytes degrade to unsupported`() = runTest {
        val service = TurnOsNotificationServiceDesktop(
            isTraySupported = { true },
            loadIconBytes = { ByteArray(0) }
        )

        assertEquals(TurnOsNotificationPermission.UNSUPPORTED, service.permissionState())
    }

    @Test
    fun `a failing icon read degrades to unsupported`() = runTest {
        val service = TurnOsNotificationServiceDesktop(
            isTraySupported = { true },
            loadIconBytes = { error("resource missing") }
        )

        assertEquals(TurnOsNotificationPermission.UNSUPPORTED, service.permissionState())
    }

    @Test
    fun `an unsupported tray degrades to unsupported and every call is a no-op`() = runTest {
        val service = TurnOsNotificationServiceDesktop(
            isTraySupported = { false },
            loadIconBytes = { ByteArray(0) }
        )

        // Requesting cannot upgrade an unsupported platform, so it reports the same state.
        assertEquals(TurnOsNotificationPermission.UNSUPPORTED, service.requestPermission())
        service.showNotification(TurnOsNotificationRequest(1L, "title", "body"))
        service.bringAppToFront()
    }
}
