package eu.torvian.chatbot.common.models.api.access

import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonUserGroups
import kotlinx.serialization.Serializable

/**
 * Details containing all access information for a resource.
 *
 * @property resourceId The ID of the resource
 * @property owner Information about the owner of the resource, if available
 * @property accessList List of groups with their access modes
 */
@Serializable
data class ResourceAccessDetails(
    val resourceId: Long,
    val owner: OwnerInfo?,
    val accessList: List<ResourceAccessInfo>
){
    /**
     * Checks if the resource is publicly accessible.
     *
     * A resource is considered public if the "All Users" group has READ access.
     *
     * @return True if the resource is public, false otherwise
     */
    fun isPublic(): Boolean = accessList.any {
        it.groupName == CommonUserGroups.ALL_USERS && it.accessMode == AccessMode.READ.key
    }

    /**
     * Checks if the resource is private.
     *
     * A resource is considered private if the "All Users" group has no access.
     *
     * @return True if the resource is private, false otherwise
     */
    fun isPrivate(): Boolean = accessList.none {
        it.groupName == CommonUserGroups.ALL_USERS
    }
}

/**
 * Information about which groups have access to a resource and what access mode.
 *
 * @property groupId The ID of the user group
 * @property groupName The name of the user group
 * @property accessMode The access mode granted (e.g., "read", "write")
 */
@Serializable
data class ResourceAccessInfo(
    val groupId: Long,
    val groupName: String,
    val accessMode: String
)