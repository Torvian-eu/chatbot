package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Use case for loading chat sessions in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Sets the activeSessionId to trigger reactive state updates
 * 2. Triggers repository load operations
 * 3. Lets the reactive system handle all state updates automatically
 * 4. Handles retry logic via EventBus subscription
 * 5. Manages retry state internally (not in ChatState)
 */
class LoadSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val modelRepository: ModelRepository,
    private val toolRepository: ToolRepository,
    private val mcpServerRepository: LocalMCPServerRepository,
    private val state: ChatState,
    private val notificationService: NotificationService,
    private val eventBus: EventBus,
    backgroundScope: CoroutineScope
) {

    private val logger = kmpLogger<LoadSessionUseCase>()

    // Internal retry state
    private var lastAttemptedSessionId: Long? = null
    private var lastFailedLoadEventId: String? = null
    private var lastUserId: Long? = null

    init {
        // Handle retry functionality via EventBus
        backgroundScope.launch {
            eventBus.events.collect { event ->
                if (event is SnackbarInteractionEvent && event.isActionPerformed) {
                    handleRetry(event.originalAppEventId)
                }
            }
        }
    }

    /**
     * Loads a chat session and its messages by ID in the reactive architecture.
     *
     * This method sets the activeSessionId and triggers repository load operations.
     * The reactive system automatically handles state updates through ChatStateImpl's
     * reactive flows.
     *
     * Note: State should be reset via clearSession() before calling this method.
     *
     * @param sessionId The ID of the session to load
     * @param userId The ID of the user, required for loading MCP servers
     */
    suspend fun execute(sessionId: Long, userId: Long) {
        logger.info("Loading session $sessionId")

        // Set the active session ID to trigger reactive state updates
        state.setActiveSessionId(sessionId)

        // Store retry state internally
        lastAttemptedSessionId = sessionId
        lastFailedLoadEventId = null
        lastUserId = userId

        // Load all dependencies in parallel
        parZip(
            { sessionRepository.loadSessionDetails(sessionId) },
            { sessionRepository.loadSessionToolCalls(sessionId) },
            { modelRepository.loadModels() },
            { modelSettingsRepository.loadAllSettings() },
            { toolRepository.loadTools() },
            { toolRepository.loadEnabledToolsForSession(sessionId) },
            { mcpServerRepository.loadServers(userId) }
        ) { sessionResult, toolCallsResult, modelsResult, settingsResult, toolsResult, enabledToolsResult, _ ->
            sessionResult
                .onLeft { error ->
                    val eventId = notificationService.repositoryError(
                        error = error,
                        shortMessageRes = Res.string.error_loading_session,
                        isRetryable = true
                    )
                    lastFailedLoadEventId = eventId
                    return@parZip
                }
                .onRight {
                    logger.info("Successfully loaded session $sessionId. Dependencies are loading in background.")
                }

            modelsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load models"
                )
                return@parZip
            }
            settingsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load model settings"
                )
                return@parZip
            }
            toolCallsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load tool calls for session"
                )
                return@parZip
            }
            toolsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load tools"
                )
                return@parZip
            }
            enabledToolsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load enabled tools for session"
                )
                return@parZip
            }
        }
        // Collapse all displayed messages after loading
        state.collapseAllDisplayedMessages()
    }

    /**
     * Handles retry requests by checking if the event ID matches and retrying if so.
     *
     * @param eventId The event ID from the retry interaction
     */
    private suspend fun handleRetry(eventId: String) {
        if (lastFailedLoadEventId == eventId && lastAttemptedSessionId != null && lastUserId != null) {
            logger.info("Retrying loadSession for session $lastAttemptedSessionId due to Snackbar action!")
            // Clear the failed event ID before retrying
            lastFailedLoadEventId = null
            execute(lastAttemptedSessionId!!, lastUserId!!)
        }
    }

    /**
     * Resets the internal state of the use case.
     * Should be called when switching sessions or clearing the chat.
     */
    fun resetState() {
        lastAttemptedSessionId = null
        lastFailedLoadEventId = null
        lastUserId = null
    }
}
