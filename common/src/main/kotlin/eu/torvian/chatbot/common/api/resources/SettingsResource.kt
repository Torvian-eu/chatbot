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
    class ById(val parent: SettingsResource = SettingsResource(), val settingsId: Long)
}

