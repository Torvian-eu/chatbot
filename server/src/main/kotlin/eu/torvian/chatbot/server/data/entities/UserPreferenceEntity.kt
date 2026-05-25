package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a persisted user preference row.
 *
 * @property id Unique identifier of the preference record.
 * @property userId Owning user identifier.
 * @property deviceId Optional device identifier for device-scoped preferences (FK to user_devices).
 * @property scopeId Scope identifier: "GLOBAL" for global, or clientDeviceId for device-specific.
 * @property prefKey Logical preference key.
 * @property prefValue Serialized preference value stored as text.
 * @property updatedAt Timestamp when the preference was last changed.
 */
data class UserPreferenceEntity(
    val id: Long,
    val userId: Long,
    val deviceId: Long?,
    val scopeId: String,
    val prefKey: String,
    val prefValue: String,
    val updatedAt: Instant
)
