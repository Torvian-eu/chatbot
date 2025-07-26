package eu.torvian.chatbot.app.domain.events

import kotlin.uuid.ExperimentalUuidApi

/**
 * Represents a global success notification.
 *
 * @property message The success message to display. (or string resource ID for localization)
 */
@OptIn(ExperimentalUuidApi::class)
abstract class GlobalSuccess(
    val message: String
) : AppEvent()