package eu.torvian.chatbot.app.navigation

/**
 * Defines the sealed class for application navigation routes.
 * Each object represents a distinct screen or a navigation path.
 */
sealed class AppRoute(val route: String) {
    /**
     * Route for the main chat interface.
     */
    object Chat : AppRoute("chat_route")

    /**
     * Route for the settings configuration interface.
     */
    object Settings : AppRoute("settings_route")

    // Future routes can be added here, potentially with arguments:
    // data class SessionDetails(val sessionId: Long) : AppRoute("session_details/{sessionId}") {
    //     fun createRoute(sessionId: Long) = "session_details/$sessionId"
    // }
}