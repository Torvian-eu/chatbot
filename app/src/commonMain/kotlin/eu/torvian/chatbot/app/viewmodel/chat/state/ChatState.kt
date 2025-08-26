package eu.torvian.chatbot.app.viewmodel.chat.state

/**
 * Interface for chat state operations.
 * This interface combines session management and user interaction state
 * to provide full access to all chat state operations.
 */
interface ChatState : SessionState, InteractionState
