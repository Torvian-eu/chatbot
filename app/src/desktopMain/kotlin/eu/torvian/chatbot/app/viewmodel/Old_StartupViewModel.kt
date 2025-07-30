package eu.torvian.chatbot.app.viewmodel

//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import eu.torvian.chatbot.app.koin.appModule
//import eu.torvian.chatbot.app.utils.misc.KmpLogger
//import eu.torvian.chatbot.app.utils.misc.kmpLogger
//import eu.torvian.chatbot.server.main.ServerControlService
//import eu.torvian.chatbot.server.main.ServerInstanceInfo
//import eu.torvian.chatbot.server.main.ServerStatus
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.runBlocking
//import org.koin.core.KoinApplication
//import org.koin.dsl.koinApplication
//
///**
// * Represents the different states of the application during startup.
// *
// * Used to manage the UI based on the current state of the application.
// *
// * - [NotStarted]: The application has not started yet.
// * - [Starting]: The application is starting.
// * - [Started]: The application has started and is running.
// * - [Error]: An error occurred during application startup or runtime.
// * - [Stopping]: The application is in the process of shutting down.
// * - [Stopped]: The application has been stopped gracefully.
// */
//sealed interface StartupUiState {
//    /** The application has not started yet. */
//    data object NotStarted : StartupUiState
//
//    /** The application is starting. */
//    data object Starting : StartupUiState
//
//    /**
//     * The application has started and is running.
//     *
//     * @property serverInstanceInfo Information about the running server instance.
//     * @property koinApp The Koin application context for dependency injection.
//     */
//    data class Started(
//        val serverInstanceInfo: ServerInstanceInfo,
//        val koinApp: KoinApplication
//    ) : StartupUiState
//
//    /**
//     * An error occurred during application startup or runtime.
//     *
//     * @property exception The exception that was thrown.
//     */
//    data class Error(val exception: Throwable) : StartupUiState
//
//    /** The application is in the process of shutting down. */
//    data object Stopping : StartupUiState
//
//    /** The application has been stopped gracefully. */
//    data object Stopped : StartupUiState
//}
//
///**
// * ViewModel responsible for managing the lifecycle of the embedded server
// * and exposing its status to the UI.
// *
// * @property serverControlService The service to manage the server's lifecycle.
// */
//@Deprecated("Not doing server startup from the client app anymore.")
//class StartupViewModel(
//    private val serverControlService: ServerControlService
//) : ViewModel() {
//    private val logger: KmpLogger = kmpLogger<StartupViewModel>()
//
//    // StateFlow to expose the server status to the UI
//    private val _startupState = MutableStateFlow<StartupUiState>(StartupUiState.NotStarted)
//    val startupState: StateFlow<StartupUiState> = _startupState.asStateFlow()
//
//    private var koinApp: KoinApplication? = null
//
//    init {
//        viewModelScope.launch {
//            serverControlService.serverStatus.collect { status ->
//                when (status) {
//                    is ServerStatus.NotStarted -> _startupState.value = StartupUiState.NotStarted
//                    is ServerStatus.Starting -> _startupState.value = StartupUiState.Starting
//                    is ServerStatus.Started -> {
//                        val koinApp = koinApplication {
//                            modules(appModule(status.serverInstanceInfo.baseUri))
//                        }
//                        this@StartupViewModel.koinApp = koinApp
//                        _startupState.value =
//                            StartupUiState.Started(status.serverInstanceInfo, koinApp)
//                    }
//
//                    is ServerStatus.Error -> _startupState.value = StartupUiState.Error(status.error)
//                    is ServerStatus.Stopping -> _startupState.value = StartupUiState.Stopping
//                    is ServerStatus.Stopped -> {
//                        koinApp?.close()
//                        koinApp = null
//                        _startupState.value = StartupUiState.Stopped
//                    }
//                }
//            }
//        }
//    }
//
//    /**
//     * Attempts to start the embedded server.
//     * The status is updated via the [startupState] flow.
//     * Should be called from the UI (e.g., in a LaunchedEffect) when the app/screen starts.
//     */
//    suspend fun startApplication() {
//        serverControlService.startSuspend()
//    }
//
//    /**
//     * Attempts to stop the embedded server gracefully.
//     * The status is updated via the [startupState] flow.
//     * This is primarily intended for cleanup in [onCleared].
//     */
//    suspend fun stopApplication() {
//        serverControlService.stopSuspend(100, 3000)
//    }
//
//    /**
//     * Stops the server and disposes of the Koin context when the ViewModel is cleared.
//     * This is called when the UI is no longer in use, e.g., when the app is closed.
//     */
//    override fun onCleared() {
//        logger.info("StartupViewModel.onCleared: Initiating application cleanup...")
//        runBlocking {
//            stopApplication()
//        }
//        super.onCleared()
//        logger.info("StartupViewModel.onCleared: Cleanup routine complete.")
//    }
//}