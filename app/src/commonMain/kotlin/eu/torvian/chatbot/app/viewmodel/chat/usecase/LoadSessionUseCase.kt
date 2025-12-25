package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for loading chat sessions in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Sets the activeSessionId to trigger reactive state updates
 * 2. Triggers repository load operations
 * 3. Lets the reactive system handle all state updates automatically
 */
class LoadSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val modelRepository: ModelRepository,
    private val toolRepository: ToolRepository,
    private val mcpServerRepository: LocalMCPServerRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<LoadSessionUseCase>()

    // Keep the last userId used for loading sessions so retry logic can reuse it
    private var lastUserId: Long? = null

    /**
     * Loads a chat session and its messages by ID in the reactive architecture.
     *
     * This method sets the activeSessionId and triggers repository load operations.
     * The reactive system automatically handles state updates through ChatStateImpl's
     * reactive flows.
     *
     * @param sessionId The ID of the session to load
     * @param userId The ID of the user, required for loading MCP servers
     */
    suspend fun execute(sessionId: Long, userId: Long) {
        logger.info("Loading session $sessionId")

        // Reset state before loading a new session
        state.resetState()
        state.setActiveSessionId(sessionId)
        // Store the session ID for potential retry
        state.setRetryState(sessionId, null)
        // Update last used userId
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
                    state.setRetryState(sessionId, eventId)
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
    }

    /**
     * Handles retry requests by checking if the event ID matches and retrying if so.
     *
     * @param eventId The event ID from the retry interaction
     * @return true if retry was performed, false otherwise
     */
    suspend fun handleRetry(eventId: String): Boolean {
        val sessionId = state.lastAttemptedSessionId.value
        return if (state.lastFailedLoadEventId.value == eventId && sessionId != null && lastUserId != null) {
            logger.info("Retrying loadSession due to Snackbar action!")
            state.clearRetryState()
            execute(sessionId, lastUserId!!)
            true
        } else {
            false
        }
    }
}
