package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import eu.torvian.chatbot.app.VersionInfo

/**
 * ViewModel for the About settings category.
 *
 * Provides read-only information about the application including name, version,
 * tagline, and external links. This ViewModel is intentionally simple with no
 * mutable state, as the About screen is purely informational.
 *
 * @property appName The application name.
 * @property appVersion The build-generated version string.
 * @property tagline A brief description of the application.
 * @property websiteUrl The project website URL.
 * @property githubUrl The project's GitHub repository URL.
 * @property redditUrl The project's Reddit community URL.
 * @property license The license information string.
 */
class AboutViewModel : ViewModel() {

    /**
     * The application name displayed in the About section.
     */
    val appName: String = "Torvian Chatbot"

    /**
     * The build-generated version string pulled from [VersionInfo.VERSION].
     */
    val appVersion: String = VersionInfo.VERSION

    /**
     * A brief tagline describing the application's purpose.
     */
    val tagline: String = "Your self-hosted AI workspace for tool execution and private LLM integration."

    /**
     * The project website URL.
     */
    val websiteUrl: String = "https://chatbot.torvian.eu"

    /**
     * The project's GitHub repository URL.
     */
    val githubUrl: String = "https://github.com/Torvian-eu/chatbot"

    /**
     * The project's Reddit community URL.
     */
    val redditUrl: String = "https://www.reddit.com/r/torvian_eu/"

    /**
     * The license information string.
     */
    val license: String = "Licensed under MIT"
}
