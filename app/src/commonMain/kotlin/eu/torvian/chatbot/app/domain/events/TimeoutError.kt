package eu.torvian.chatbot.app.domain.events

import eu.torvian.chatbot.app.service.misc.EventBus

/**
 * Represents a timeout error when waiting for an event.
 *
 * This error is returned when [EventBus.awaitFirst] times out before receiving
 * the expected event.
 */
object TimeoutError
