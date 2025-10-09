package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/models endpoints.
 */
@Resource("models")
class ModelResource(val parent: Api = Api()) {
    /**
     * Resource for a specific model by ID: /api/v1/models/{modelId}
     */
    @Resource("{modelId}")
    class ById(val parent: ModelResource = ModelResource(), val modelId: Long) {
        /**
         * Resource for settings nested under a model: /api/v1/models/{modelId}/settings
         */
        @Resource("settings")
        class Settings(val parent: ById) // Note: Handles GET (list) and POST (add)

        /**
         * Resource for getting API key status for a model: /api/v1/models/{modelId}/apikey/status
         */
        @Resource("apikey/status")
        class ApiKeyStatus(val parent: ById)

        /**
         * Resource for managing model access: /api/v1/models/{modelId}/access
         */
        @Resource("access")
        class Access(val parent: ById)
    }
}

