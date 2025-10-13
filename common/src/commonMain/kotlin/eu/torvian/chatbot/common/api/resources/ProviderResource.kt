package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/providers endpoints.
 */
@Resource("providers")
class ProviderResource(val parent: Api = Api()) {
    /**
     * Resource for getting detailed information for all providers: /api/v1/providers/details
     */
    @Resource("details")
    class Details(val parent: ProviderResource = ProviderResource())

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

        /**
         * Resource for making a provider public: /api/v1/providers/{providerId}/make-public
         */
        @Resource("make-public")
        data class MakePublic(val parent: ById)

        /**
         * Resource for making a provider private: /api/v1/providers/{providerId}/make-private
         */
        @Resource("make-private")
        data class MakePrivate(val parent: ById)

        /**
         * Resource for getting provider details: /api/v1/providers/{providerId}/details
         */
        @Resource("details")
        class Details(val parent: ById)
    }
}
