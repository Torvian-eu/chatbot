package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO

/**
 * State contract for the Appearance tab.
 *
 * @property isDeviceScoped Whether the preference scope is device-scoped.
 * @property detailedPreferences Map of all detailed preferences for display.
 */
data class AppearanceTabState(
    val isDeviceScoped: Boolean,
    val detailedPreferences: Map<String, PreferenceDetailDTO>
)
