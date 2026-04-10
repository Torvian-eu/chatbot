package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.security.AuthenticationService
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

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
    private val chatService: ChatService,
    private val toolService: ToolService,
    private val toolCallService: ToolCallService,
    private val localMCPServerService: LocalMCPServerService,
    private val localMCPToolDefinitionService: LocalMCPToolDefinitionService,
    private val authenticationService: AuthenticationService,
    private val userService: UserService,
    private val userGroupService: UserGroupService,
    private val roleService: RoleService,
    private val authorizationService: AuthorizationService,
    private val workerService: WorkerService,
    private val jwtConfig: JwtConfig,
    private val json: Json
) {
    /**
     * Configures the API routes using the Ktor Resources plugin.
     * Gets the root Route from the Application instance and defines routes within it.
     */
    fun configureAllRoutes(route: Route) {
        configureAuthRoutes(route)
        configureWorkerRoutes(route)
        configureUserRoutes(route)
        configureUserGroupRoutes(route)
        configureRoleRoutes(route)
        configureSessionRoutes(route)
        configureGroupRoutes(route)
        configureProviderRoutes(route)
        configureModelRoutes(route)
        configureSettingsRoutes(route)
        configureMessageRoutes(route)
        configureToolRoutes(route)
        configureLocalMCPServerRoutes(route)
        configureLocalMCPToolRoutes(route)
    }

    /**
     * Configures routes related to Authentication (/api/v1/auth).
     */
    fun configureAuthRoutes(route: Route) {
        route.configureAuthRoutes(authenticationService, userService, workerService, jwtConfig)
    }

    /**
     * Configures routes related to Worker Management (/api/v1/workers).
     */
    fun configureWorkerRoutes(route: Route) {
        route.configureWorkerRoutes(workerService)
    }


    /**
     * Configures routes related to User Management (/api/v1/users).
     */
    fun configureUserRoutes(route: Route) {
        route.configureUserRoutes(userService, authorizationService)
    }

    /**
     * Configures routes related to User Group Management (/api/v1/user-groups).
     */
    fun configureUserGroupRoutes(route: Route) {
        route.configureUserGroupRoutes(userGroupService, authorizationService)
    }

    /**
     * Configures routes related to Role Management (/api/v1/roles).
     */
    fun configureRoleRoutes(route: Route) {
        route.configureRoleRoutes(roleService, authorizationService)
    }

    /**
     * Configures routes related to Sessions (/api/v1/sessions).
     */
    fun configureSessionRoutes(route: Route) {
        route.configureSessionRoutes(
            sessionService,
            modelSettingsService,
            chatService,
            toolCallService,
            authorizationService,
            json
        )
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
        route.configureProviderRoutes(llmProviderService, llmModelService, authorizationService)
    }

    /**
     * Configures routes related to Models (/api/v1/models).
     */
    fun configureModelRoutes(route: Route) {
        route.configureModelRoutes(llmModelService, modelSettingsService, authorizationService)
    }

    /**
     * Configures routes related to Settings (/api/v1/settings).
     */
    fun configureSettingsRoutes(route: Route) {
        route.configureSettingsRoutes(modelSettingsService, authorizationService)
    }

    /**
     * Configures routes related to Messages (/api/v1/messages)
     */
    fun configureMessageRoutes(route: Route) {
        route.configureMessageRoutes(messageService, authorizationService)
    }

    /**
     * Configures routes related to Tool Management (/api/v1/tools).
     */
    fun configureToolRoutes(route: Route) {
        route.configureToolRoutes(toolService, authorizationService)
    }

    /**
     * Configures routes related to Local MCP Server ID management (/api/v1/mcp-servers).
     */
    fun configureLocalMCPServerRoutes(route: Route) {
        route.configureLocalMCPServerRoutes(localMCPServerService)
    }

    /**
     * Configures routes related to Local MCP Tool management (/api/v1/local-mcp-tools).
     */
    fun configureLocalMCPToolRoutes(route: Route) {
        route.configureLocalMCPToolRoutes(localMCPToolDefinitionService, localMCPServerService)
    }
}