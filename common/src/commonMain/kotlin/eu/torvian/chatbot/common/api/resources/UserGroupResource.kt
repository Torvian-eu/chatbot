package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/user-groups endpoints.
 *
 * These endpoints allow administrators to manage user groups used for
 * resource sharing and access control.
 */
@Resource("user-groups")
class UserGroupResource(val parent: Api = Api()) {
    /**
     * Resource for a specific user group by ID: /api/v1/user-groups/{groupId}
     */
    @Resource("{groupId}")
    class ById(val parent: UserGroupResource = UserGroupResource(), val groupId: Long) {
        /**
         * Resource for group members: /api/v1/user-groups/{groupId}/members
         */
        @Resource("members")
        class Members(val parent: ById) {
            /**
             * Resource for a specific member: /api/v1/user-groups/{groupId}/members/{userId}
             */
            @Resource("{userId}")
            class ByUserId(val parent: Members, val userId: Long)
        }
    }
}