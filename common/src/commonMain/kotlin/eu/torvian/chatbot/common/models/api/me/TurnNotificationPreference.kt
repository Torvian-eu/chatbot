package eu.torvian.chatbot.common.models.api.me

import kotlinx.serialization.Serializable

/**
 * Serialized per-user turn-status notification toggles.
 *
 * Stored as the string value of the well-known [PreferenceKeys.TURN_NOTIFICATIONS] preference so the
 * client settings UI and the dispatcher share one decoding contract. Every field is optional on the
 * wire: a value written by an older client that omits a field decodes with that field's default
 * (`true`), which keeps the feature forward compatible without a migration.
 *
 * An absent preference row means the user has never configured the feature, and the client applies
 * [DEFAULT] instead of treating the absence as disabled.
 *
 * @property enabled Master toggle: when `false`, no sound and no OS notification is produced for any
 *           trigger, and the channel flags are ignored.
 * @property soundEnabled Whether the bundled notification sound may play for a trigger; only
 *           consulted while [enabled] is `true`.
 * @property osNotificationEnabled Whether an OS notification may be shown for a trigger; only
 *           consulted while [enabled] is `true`. A platform that cannot show one degrades to
 *           sound-only regardless of this flag.
 */
@Serializable
data class TurnNotificationPreference(
    val enabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val osNotificationEnabled: Boolean = true
) {
    companion object {
        /**
         * Default toggles applied when the server holds no stored [PreferenceKeys.TURN_NOTIFICATIONS]
         * row. The feature is opt-out: the focus gate already prevents signals while the user is
         * looking at the app, so a fresh user gets notifications until they turn them off.
         */
        val DEFAULT: TurnNotificationPreference = TurnNotificationPreference()
    }
}
