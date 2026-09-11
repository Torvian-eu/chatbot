package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/tools endpoints.
 *
 * These resources define type-safe routes for managing tool definitions
 * and user tool approval preferences.
 */
@Resource("tools")
class ToolResource(val parent: Api = Api()) {
    /**
     * Resource for a specific tool by ID: /api/v1/tools/{toolId}
     */
    @Resource("{toolId}")
    class ById(val parent: ToolResource = ToolResource(), val toolId: Long)

    /**
     * Resource for user tool approval preferences: /api/v1/tools/approval-preferences
     */
    @Resource("approval-preferences")
    class ApprovalPreferences(val parent: ToolResource = ToolResource()) {
        /**
         * Resource for a specific approval preference by tool ID: /api/v1/tools/approval-preferences/{toolId}
         */
        @Resource("{toolId}")
        class ByToolId(val parent: ApprovalPreferences = ApprovalPreferences(), val toolId: Long)
    }
}

