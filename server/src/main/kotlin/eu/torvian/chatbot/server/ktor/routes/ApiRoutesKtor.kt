package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.security.AuthenticationService
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.server.routing.*

/**
 * Ktor route configuration using type-safe Resources plugin.
 * Implements the ApiRoutes interface and uses injected dependencies.
 */
class ApiRoutesKtor(
    private val sessionService: SessionService,
    private val groupService: GroupService,
    private val llmProviderService: LLMProviderService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val messageService: MessageService,
    private val authenticationService: AuthenticationService,
    private val userService: UserService,
    private val authorizationService: AuthorizationService
) {
    /**
     * Configures the API routes using the Ktor Resources plugin.
     * Gets the root Route from the Application instance and defines routes within it.
     */
    fun configureAllRoutes(route: Route) {
        configureAuthRoutes(route)
        configureUserRoutes(route)
        configureSessionRoutes(route)
        configureGroupRoutes(route)
        configureProviderRoutes(route)
        configureModelRoutes(route)
        configureSettingsRoutes(route)
        configureMessageRoutes(route)
    }

    /**
     * Configures routes related to Authentication (/api/v1/auth).
     */
    fun configureAuthRoutes(route: Route) {
        route.configureAuthRoutes(authenticationService, userService)
    }

    /**
     * Configures routes related to User Management (/api/v1/users).
     */
    fun configureUserRoutes(route: Route) {
        route.configureUserRoutes(userService, authorizationService)
    }

    /**
     * Configures routes related to Sessions (/api/v1/sessions).
     */
    fun configureSessionRoutes(route: Route) {
        route.configureSessionRoutes(sessionService, messageService, authorizationService)
    }

    /**
     * Configures routes related to Groups (/api/v1/groups).
     */
    fun configureGroupRoutes(route: Route) {
        route.configureGroupRoutes(groupService, authorizationService)
    }

    /**
     * Configures routes related to Providers (/api/v1/providers).
     */
    fun configureProviderRoutes(route: Route) {
        route.configureProviderRoutes(llmProviderService, llmModelService)
    }

    /**
     * Configures routes related to Models (/api/v1/models).
     */
    fun configureModelRoutes(route: Route) {
        route.configureModelRoutes(llmModelService, modelSettingsService)
    }

    /**
     * Configures routes related to Settings (/api/v1/settings).
     */
    fun configureSettingsRoutes(route: Route) {
        route.configureSettingsRoutes(modelSettingsService)
    }

    /**
     * Configures routes related to Messages (/api/v1/messages)
     */
    fun configureMessageRoutes(route: Route) {
        route.configureMessageRoutes(messageService, authorizationService)
    }
}