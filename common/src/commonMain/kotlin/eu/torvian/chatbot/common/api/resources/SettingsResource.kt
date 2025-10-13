package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the top-level /api/v1/settings/{settingsId} endpoint.
 * (Model-specific settings are defined within ModelResources)
 */
@Resource("settings")
class SettingsResource(val parent: Api = Api()) {
    /**
     * Resource for getting detailed information for all settings: /api/v1/settings/details
     */
    @Resource("details")
    class Details(val parent: SettingsResource = SettingsResource())

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
         * Resource for getting settings details: /api/v1/settings/{settingsId}/details
         */
        @Resource("details")
        class Details(val parent: ById)
    }
}
