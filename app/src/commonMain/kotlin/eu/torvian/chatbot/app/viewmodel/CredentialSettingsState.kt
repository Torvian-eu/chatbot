package eu.torvian.chatbot.app.viewmodel

import eu.torvian.chatbot.app.domain.contracts.AgentRoleSection
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel

/**
 * Roles a credential profile may be linked to, derived once from the role and project streams.
 *
 * The picker renders [sections], and the profile card resolves the labels of a profile's linked ids
 * through [labelsById]. Both fields come from the same inputs, so the card can never contradict the
 * picker.
 *
 * @property sections Roles grouped per project, project-name ascending with the unassociated roles
 *   last, as the grouped picker renders them.
 * @property labelsById Card label per role id: the role label plus its project, because a card row
 *   has no grouping to carry that context.
 */
data class CredentialRoleOptions(
    val sections: List<AgentRoleSection>,
    val labelsById: Map<Long, String>,
)

/**
 * Editable state of one credential profile form.
 *
 * `environmentEntries` and `askpassVariables` are plain strings in the editor: the operator types a
 * newline-separated list of askpass variable names and one `NAME=value` pair per line for the
 * non-secret environment, which keeps the dialog small without hiding the format.
 *
 * @property profileId Profile being edited, or null when the dialog creates a new one.
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name, and the only profile field the model may name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the secret that supplies the value.
 * @property command Executable the profile starts.
 * @property argsText Arguments, one per line.
 * @property channel Delivery channel.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property askpassVariablesText Askpass variable names, one per line.
 * @property environmentText Non-secret environment entries, one `NAME=value` pair per line.
 * @property isEnabled Whether the profile may be attached.
 * @property agentRoleIds Agent roles allowed to attach the profile. An empty set is a draft that
 *   nobody may attach; it is saved as such rather than rejected.
 * @property violations Validation messages of the last failed save.
 */
data class CredentialProfileFormState(
    val profileId: Long? = null,
    val workerId: Long? = null,
    val name: String = "",
    val description: String = "",
    val secretRef: String = "",
    val command: String = "",
    val argsText: String = "",
    val channel: CredentialChannel = CredentialChannel.ASKPASS,
    val envName: String = "",
    val fileName: String = "",
    val askpassUsername: String = "",
    val askpassVariablesText: String = "",
    val environmentText: String = "",
    val isEnabled: Boolean = true,
    val agentRoleIds: Set<Long> = emptySet(),
    val violations: List<String> = emptyList(),
)

/**
 * State of one secret form.
 *
 * A stored secret is never read back, so editing offers replacement only: the dialog never shows an
 * existing value and never pre-fills the field.
 *
 * @property secretId Secret being replaced, or null when the dialog creates a new one.
 * @property workerId Worker the secret is bound to.
 * @property reference Reference a profile will use.
 * @property value New plaintext value, held only until the request completes.
 * @property isReplace Whether the dialog replaces an existing value.
 * @property violations Validation messages of the last failed save.
 */
data class CredentialSecretFormState(
    val secretId: Long? = null,
    val workerId: Long? = null,
    val reference: String = "",
    val value: String = "",
    val isReplace: Boolean = false,
    val violations: List<String> = emptyList(),
)

/**
 * Dialog currently shown on the credential settings surface.
 */
sealed interface CredentialSettingsDialog {
    /** No dialog is open. */
    data object None : CredentialSettingsDialog

    /**
     * Profile editor.
     *
     * @property form Editable form state.
     */
    data class Profile(val form: CredentialProfileFormState) : CredentialSettingsDialog

    /**
     * Secret editor.
     *
     * @property form Editable form state.
     */
    data class Secret(val form: CredentialSecretFormState) : CredentialSettingsDialog

    /**
     * Deletion confirmation.
     *
     * @property isSecret Whether the target is a secret rather than a profile.
     * @property id Identifier of the row to delete.
     * @property label Name or reference shown in the confirmation.
     */
    data class ConfirmDelete(
        val isSecret: Boolean,
        val id: Long,
        val label: String,
    ) : CredentialSettingsDialog
}
