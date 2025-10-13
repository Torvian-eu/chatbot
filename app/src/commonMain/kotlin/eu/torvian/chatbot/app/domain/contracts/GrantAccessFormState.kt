package eu.torvian.chatbot.app.domain.contracts

/**
 * Represents the state of the grant access form,
 * used for managing access to resources like providers and models.
 *
 * @property selectedGroupId The ID of the selected user group.
 * @property selectedAccessMode The selected access mode.
 * @property availableAccessModes The available access modes.
 * @property dropdownExpanded Whether the dropdown is expanded.
 */
data class GrantAccessFormState(
    val selectedGroupId: Long? = null,
    val selectedAccessMode: String = "read",
    val availableAccessModes: List<String> = listOf("read", "write"),
    val dropdownExpanded: Boolean = false
)