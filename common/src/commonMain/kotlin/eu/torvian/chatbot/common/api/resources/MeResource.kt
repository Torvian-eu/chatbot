package eu.torvian.chatbot.common.api.resources

import eu.torvian.chatbot.common.models.user.PreferenceScope
import io.ktor.resources.*

/**
 * Resource tree for authenticated self-service endpoints under /api/v1/me.
 */
@Resource("me")
class MeResource(val parent: Api = Api()) {
    /**
     * Resource for the authenticated user's preference collection: /api/v1/me/preferences.
     */
    @Resource("preferences")
    class Preferences(val parent: MeResource = MeResource()) {
        /**
         * Resource for a single preference key: /api/v1/me/preferences/{key}.
         *
         * @property key Preference key to read or update.
         * @property scope Optional scope for DELETE operations. When specified, only the preference
         *                   in the given scope is deleted. If omitted, defaults to DEVICE scope.
         */
        @Resource("{key}")
        data class ByKey(
            val parent: Preferences = Preferences(),
            val key: String,
            val scope: PreferenceScope? = null
        )

        /**
         * Resource for detailed preference view showing global and device values: /api/v1/me/preferences/details.
         *
         * This endpoint returns a map of preference keys to their detailed values,
         * allowing the UI to display the inheritance chain.
         */
        @Resource("details")
        class Details(val parent: Preferences = Preferences())
    }
}
