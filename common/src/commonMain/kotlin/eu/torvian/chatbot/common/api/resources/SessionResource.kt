package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/sessions endpoints.
 */
@Resource("sessions")
class SessionResource(val parent: Api = Api()) {
    /**
     * Resource for a specific session by ID: /api/v1/sessions/{sessionId}
     */
    @Resource("{sessionId}")
    class ById(val parent: SessionResource = SessionResource(), val sessionId: Long) {
        /**
         * Resource for updating a session's name: /api/v1/sessions/{sessionId}/name
         */
        @Resource("name")
        class Name(val parent: ById)

        /**
         * Resource for updating a session's model: /api/v1/sessions/{sessionId}/model
         */
        @Resource("model")
        class Model(val parent: ById)

        /**
         * Resource for updating a session's settings: /api/v1/sessions/{sessionId}/settings
         */
        @Resource("settings")
        class Settings(val parent: ById)

        /**
         * Resource for updating a session's leaf message: /api/v1/sessions/{sessionId}/leafMessage
         */
        @Resource("leafMessage")
        class LeafMessage(val parent: ById)

        /**
         * Resource for updating a session's group: /api/v1/sessions/{sessionId}/group
         */
        @Resource("group")
        class Group(val parent: ById)

        /**
         * Resource for messages nested under a session: /api/v1/sessions/{sessionId}/messages
         */
        @Resource("messages")
        class Messages(val parent: ById)

        /**
         * Resource for tool calls nested under a session: /api/v1/sessions/{sessionId}/toolcalls
         */
        @Resource("toolcalls")
        class ToolCalls(val parent: ById)
    }
}

