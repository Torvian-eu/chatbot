package eu.torvian.chatbot.common.models.api.access

import kotlinx.serialization.Serializable

/**
 * Response indicating whether a resource is publicly accessible.
 *
 * A resource is considered public if the "All Users" group has READ access to it.
 *
 * @property isPublic True if the resource is shared with the "All Users" group (with READ access)
 * @property hasAllUsersReadAccess Explicit flag for "All Users" READ access (same as isPublic)
 * @property hasAllUsersWriteAccess True if the "All Users" group has WRITE access
 * @property hasAllUsersOtherAccess True if the "All Users" group has any access other than READ or WRITE
 */
@Serializable
data class IsPublicResponse(
    val hasAllUsersReadAccess: Boolean,
    val hasAllUsersWriteAccess: Boolean,
    val hasAllUsersOtherAccess: Boolean
){
    val isPublic: Boolean get() = hasAllUsersReadAccess
}