package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Desktop [TurnOsNotificationService] built on the AWT system tray.
 *
 * A tray icon is installed lazily on first use, because a user who never enables notifications never
 * pays for the icon. On platforms where the tray or its notification daemon is unavailable the
 * service reports [TurnOsNotificationPermission.UNSUPPORTED], and the dispatcher keeps playing the
 * sound. Show calls use the tray's balloon/message API, which cannot throw into the turn.
 *
 * Click-through is best effort: AWT reports only that the tray icon was clicked, never which balloon
 * was clicked, so a click opens the session of the most recent alert. A click on an older balloon
 * while a newer alert is on screen therefore selects the newer session.
 *
 * The degradation decisions (tray unsupported, undecodable or unreadable icon) are unit-tested through
 * the injectable bytes supplier and tray-availability probe, which never need a real tray. Whether the
 * platform hosts a tray and whether the desktop environment renders a balloon depend on the machine and
 * cannot be exercised in a unit test.
 *
 * @property loadIconBytes Supplier of the tray icon image. Defaults to the bundled Compose Resource;
 *           injectable so a test can exercise the undecodable-icon degradation without a real tray.
 * @property isTraySupported Probe for platform tray availability. Defaults to the AWT query;
 *           injectable so a test can reach the icon-failure path on a machine that has no tray.
 */
class TurnOsNotificationServiceDesktop(
    private val loadIconBytes: suspend () -> ByteArray = { Res.readBytes(ICON_RESOURCE_PATH) },
    private val isTraySupported: () -> Boolean = { SystemTray.isSupported() }
) : TurnOsNotificationService {

    companion object {
        /** Classpath path of the bundled tray icon inside the Compose Resources file tree. */
        private const val ICON_RESOURCE_PATH = "files/notification/app-icon.png"

        private val logger = kmpLogger<TurnOsNotificationServiceDesktop>()

        /** Tooltip and fallback label for the tray icon. */
        private const val TRAY_ICON_TOOLTIP = "Torvian chatbot"
    }

    private val mutableClickedSessionIds = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    override val clickedSessionIds: Flow<Long> = mutableClickedSessionIds.asSharedFlow()

    /** Installed tray icon, or `null` while notifications are unavailable. */
    private var trayIcon: TrayIcon? = null

    /** Session of the most recent notification; see the class documentation for why a click cannot
     * name a specific balloon. */
    private var lastNotifiedSessionId: Long? = null

    override suspend fun permissionState(): TurnOsNotificationPermission =
        if (withContext(Dispatchers.Swing) { ensureTrayIcon() } != null) {
            TurnOsNotificationPermission.GRANTED
        } else {
            TurnOsNotificationPermission.UNSUPPORTED
        }

    override suspend fun requestPermission(): TurnOsNotificationPermission = permissionState()

    override suspend fun showNotification(request: TurnOsNotificationRequest) {
        lastNotifiedSessionId = request.sessionId
        withContext(Dispatchers.Swing) {
            runCatching {
                val icon = ensureTrayIcon() ?: return@runCatching
                // Logged before the call because AWT reports no failure when the desktop environment
                // suppresses a balloon, which would otherwise make a silent skip indistinguishable
                // from a notification that the user simply did not see.
                logger.info("Showing turn notification for session ${request.sessionId}")
                icon.displayMessage(request.title, request.body, TrayIcon.MessageType.INFO)
            }.onFailure { failure ->
                logger.warn("Failed to show a turn notification: ${failure.message}", failure)
            }
        }
    }

    override suspend fun bringAppToFront() {
        withContext(Dispatchers.Swing) {
            runCatching {
                // AWT window operations must run on the event dispatch thread, which is why this
                // whole call is dispatched there; a window that only needs focus is still raised so
                // it is not left behind another application's window.
                java.awt.Window.getWindows()
                    .filter { it.isVisible }
                    .forEach { window ->
                        window.toFront()
                        window.requestFocus()
                    }
            }.onFailure { failure ->
                logger.warn("Failed to raise the application window: ${failure.message}", failure)
            }
        }
    }

    /**
     * Installs the tray icon once and reports it, or `null` when this platform cannot host one.
     *
     * Must be called on the event dispatch thread.
     *
     * @return The usable tray icon, or `null` when the tray is unsupported or icon loading failed.
     */
    private suspend fun ensureTrayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        return runCatching {
            if (!isTraySupported()) {
                logger.warn("System tray is not supported on this platform; OS notifications are unavailable")
                return@runCatching null
            }
            val image = loadTrayImage() ?: return@runCatching null
            val icon = TrayIcon(image, TRAY_ICON_TOOLTIP).apply {
                isImageAutoSize = true
                // AWT offers no per-balloon click callback, so the icon click is the only affordance
                // and it can only resolve the session of the most recent alert.
                addActionListener {
                    lastNotifiedSessionId?.let { mutableClickedSessionIds.tryEmit(it) }
                }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            logger.info("Desktop tray icon installed; OS notifications are available")
            icon
        }.onFailure { failure ->
            logger.warn("System tray notifications are unavailable: ${failure.message}", failure)
        }.getOrNull()
    }

    /**
     * Decodes the bundled tray icon.
     *
     * @return The decoded image, or `null` when the resource is missing or undecodable.
     */
    private suspend fun loadTrayImage(): BufferedImage? = runCatching {
        ImageIO.read(loadIconBytes().inputStream())
    }.onFailure { failure ->
        logger.warn("Failed to load the tray icon: ${failure.message}", failure)
    }.getOrNull()
}
