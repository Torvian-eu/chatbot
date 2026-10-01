package eu.torvian.chatbot.app.compose.chatarea

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats an instant as the local date and zero-padded `HH:mm` time of day used by the chat area detail dialogs.
 *
 * Seconds and the time zone are omitted so the value stays compact; the device's current time zone is applied.
 *
 * @param instant Instant to format.
 * @return The local `date HH:mm` representation of [instant].
 */
internal fun formatInstant(instant: Instant): String {
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.date} ${localDateTime.hour.toString().padStart(2, '0')}:${
        localDateTime.minute.toString().padStart(2, '0')
    }"
}
