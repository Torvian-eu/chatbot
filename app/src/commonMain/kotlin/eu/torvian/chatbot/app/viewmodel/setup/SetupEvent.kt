package eu.torvian.chatbot.app.viewmodel.setup

/**
 * Sealed interface representing all possible user actions in the setup screen.
 *
 * Using a sealed interface ensures type safety and exhaustive when expressions.
 * Each event represents a single user intent.
 */
sealed interface SetupEvent {
    /**
     * User changed the server URL.
     */
    data class ServerUrlChanged(val url: String) : SetupEvent

    /**
     * User changed the data directory.
     */
    data class DataDirChanged(val dir: String) : SetupEvent

    /**
     * User toggled encryption key visibility.
     */
    data object ToggleKeyVisibility : SetupEvent

    /**
     * User clicked "Complete Setup" button.
     */
    data object CompleteSetup : SetupEvent

    /**
     * User dismissed the error message.
     */
    data object DismissError : SetupEvent
}

