package eu.torvian.chatbot.common.models.api.access

import kotlinx.serialization.Serializable

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

/**
 * Response containing all access information for a resource.
 *
 * @property resourceId The ID of the resource
 * @property ownerId The ID of the resource owner
 * @property accessList List of groups with their access modes
 */
@Serializable
data class ResourceAccessResponse(
    val resourceId: Long,
    val ownerId: Long,
    val accessList: List<ResourceAccessInfo>
)