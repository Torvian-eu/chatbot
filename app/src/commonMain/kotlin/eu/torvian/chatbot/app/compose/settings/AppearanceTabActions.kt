package eu.torvian.chatbot.app.compose.settings

/**
 * Action callbacks for the Appearance tab.
 */
interface AppearanceTabActions {
    /**
     * Sets the theme preference.
     *
     * @param theme The theme to set, or `null` to use system default.
     */
    fun onSetTheme(theme: String?)

    /**
     * Sets whether the preference scope is device-scoped.
     *
     * @param isDeviceScoped `true` for device scope, `false` for global scope.
     */
    fun onSetDeviceScoped(isDeviceScoped: Boolean)
}
