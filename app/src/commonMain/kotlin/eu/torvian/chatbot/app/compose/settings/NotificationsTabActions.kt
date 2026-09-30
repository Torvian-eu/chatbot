package eu.torvian.chatbot.app.compose.settings

/**
 * Action callbacks for the Notifications tab.
 */
interface NotificationsTabActions {

    /**
     * Sets the master toggle for turn alerts.
     *
     * @param enabled Whether turn alerts should be produced at all.
     */
    fun onSetEnabled(enabled: Boolean)

    /**
     * Sets whether the bundled alert sound may play.
     *
     * @param enabled Whether the sound channel is available.
     */
    fun onSetSoundEnabled(enabled: Boolean)

    /**
     * Sets whether OS notifications may be shown.
     *
     * @param enabled Whether the OS notification channel is available.
     */
    fun onSetOsNotificationEnabled(enabled: Boolean)

    /**
     * Asks the platform for OS-notification permission.
     *
     * Tied to a user gesture because Android and browsers only grant the permission from one.
     */
    fun onRequestPermission()
}
