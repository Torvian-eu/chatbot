package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/groups endpoints.
 */
@Resource("groups")
class GroupResource(val parent: Api = Api()) {
    /**
     * Resource for a specific group by ID: /api/v1/groups/{groupId}
     */
    @Resource("{groupId}")
    class ById(val parent: GroupResource = GroupResource(), val groupId: Long)
}

