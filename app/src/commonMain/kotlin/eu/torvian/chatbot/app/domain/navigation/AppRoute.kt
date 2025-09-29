package eu.torvian.chatbot.app.domain.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the contract for application navigation routes.
 *
 * @property name The name of the route, used for serialization.
 * @property route The actual route string, used for navigation.
 */
interface AppRoute {
    val name: String
    val route: String
}

// --- Authentication Routes ---

/**
 * Route for the authentication loading/splash screen.
 */
@Serializable
@SerialName("auth_loading")
object AuthLoading : AppRoute {
    override val name = "auth_loading"
    override val route = "auth_loading"
}

/**
 * Route for the login screen.
 */
@Serializable
@SerialName("login")
object Login : AppRoute {
    override val name = "login"
    override val route = "login"
}

/**
 * Route for the registration screen.
 */
@Serializable
@SerialName("register")
object Register : AppRoute {
    override val name = "register"
    override val route = "register"
}

// --- Main Application Routes ---

/**
 * Route for the main chat interface.
 */
@Serializable
@SerialName("chat")
object Chat : AppRoute {
    override val name = "chat"
    override val route = "chat"
}

/**
 * Route for the settings configuration interface.
 */
@Serializable
@SerialName("settings")
object Settings : AppRoute {
    override val name = "settings"
    override val route = "settings"
}

// Future routes can be added here, potentially with arguments:

// @Serializable
// @SerialName("session_details")
// object SessionDetails : AppRoute {
//     override val name = "session_details"
//     override val route = "session_details/{sessionId}"
// }
