package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/users endpoints (admin user management).
 */
@Resource("users")
class UserResource(val parent: Api = Api()) {
    /**
     * Resource for a specific user by ID: /api/v1/users/{userId}
     */
    @Resource("{userId}")
    class ById(val parent: UserResource = UserResource(), val userId: Long) {
        /**
         * Resource for user roles: /api/v1/users/{userId}/roles
         */
        @Resource("roles")
        class Roles(val parent: ById) {
            /**
             * Resource for a specific role assignment: /api/v1/users/{userId}/roles/{roleId}
             */
            @Resource("{roleId}")
            class ByRoleId(val parent: Roles, val roleId: Long)
        }

        /**
         * Resource for password management: /api/v1/users/{userId}/password
         */
        @Resource("password")
        class Password(val parent: ById)
    }
}
