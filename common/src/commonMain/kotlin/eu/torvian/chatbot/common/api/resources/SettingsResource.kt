package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the top-level /api/v1/settings/{settingsId} endpoint.
 * (Model-specific settings are defined within ModelResources)
 */
@Resource("settings")
class SettingsResource(val parent: Api = Api()) {
    /**
     * Resource for a specific setting by ID: /api/v1/settings/{settingsId}
     */
    @Resource("{settingsId}")
    class ById(val parent: SettingsResource = SettingsResource(), val settingsId: Long) {
        /**
         * Resource for managing settings access: /api/v1/settings/{settingsId}/access
         */
        @Resource("access")
        class Access(val parent: ById)

        /**
         * Resource for making a settings profile public: /api/v1/settings/{settingsId}/make-public
         */
        @Resource("make-public")
        data class MakePublic(val parent: ById)

        /**
         * Resource for making a settings profile private: /api/v1/settings/{settingsId}/make-private
         */
        @Resource("make-private")
        data class MakePrivate(val parent: ById)

        /**
         * Resource for checking if a settings profile is public: /api/v1/settings/{settingsId}/is-public
         */
        @Resource("is-public")
        data class IsPublic(val parent: ById)

        /**
         * Resource for getting settings owner information: /api/v1/settings/{settingsId}/owner
         */
        @Resource("owner")
        data class Owner(val parent: ById)
    }
}
