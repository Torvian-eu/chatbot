package eu.torvian.chatbot.app.service.auth

import eu.torvian.chatbot.app.domain.events.InternalEvent

/**
 * Represents an authentication failure event.
 * This event is triggered when an API request fails due to authentication issues.
 */
class AuthenticationFailureEvent(
    val reason: String? = null
) : InternalEvent()