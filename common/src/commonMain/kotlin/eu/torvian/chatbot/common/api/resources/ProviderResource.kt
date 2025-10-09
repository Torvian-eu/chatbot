package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/providers endpoints.
 */
@Resource("providers")
class ProviderResource(val parent: Api = Api()) {
    /**
     * Resource for a specific provider by ID: /api/v1/providers/{providerId}
     */
    @Resource("{providerId}")
    class ById(val parent: ProviderResource = ProviderResource(), val providerId: Long) {
        /**
         * Resource for updating a provider's credential: /api/v1/providers/{providerId}/credential
         */
        @Resource("credential")
        class Credential(val parent: ById)

        /**
         * Resource for getting models for a provider: /api/v1/providers/{providerId}/models
         */
        @Resource("models")
        class Models(val parent: ById)

        /**
         * Resource for managing provider access: /api/v1/providers/{providerId}/access
         */
        @Resource("access")
        class Access(val parent: ById)
    }
}

