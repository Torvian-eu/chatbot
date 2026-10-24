package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.CredentialDispatchWarning
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.CredentialProfileFormState
import eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsDialog
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * State of the credential settings surface.
 *
 * @property workers Workers a profile or secret can be bound to.
 * @property selectedWorkerId Worker currently shown, or null before the first selection.
 * @property profiles Profiles of the selected worker.
 * @property secrets Secret metadata of the selected worker; no value is ever present.
 * @property dialog Dialog currently open.
 * @property dispatchWarning Persisted change the worker has not accepted yet, if any.
 */
data class CredentialSettingsState(
    val workers: DataState<RepositoryError, List<WorkerDto>>,
    val selectedWorkerId: Long?,
    val profiles: DataState<RepositoryError, List<CredentialProfileDto>>,
    val secrets: DataState<RepositoryError, List<CredentialSecretMetadataDto>>,
    val dialog: CredentialSettingsDialog,
    val dispatchWarning: CredentialDispatchWarning?,
)

/**
 * Actions available on the credential settings surface.
 */
interface CredentialSettingsActions {
    /** Loads the worker picker and the selected worker's rows. */
    fun onLoad()

    /**
     * @param workerId Worker to show, or null to clear the selection.
     */
    fun onSelectWorker(workerId: Long?)

    /** Opens the editor for a new profile. */
    fun onStartAddingProfile()

    /**
     * @param profile Profile to edit.
     */
    fun onStartEditingProfile(profile: CredentialProfileDto)

    /**
     * @param profile Profile awaiting deletion.
     */
    fun onStartDeletingProfile(profile: CredentialProfileDto)

    /** Opens the editor for a new secret. */
    fun onStartAddingSecret()

    /**
     * @param secret Secret whose value will be replaced.
     */
    fun onStartReplacingSecret(secret: CredentialSecretMetadataDto)

    /**
     * @param secret Secret awaiting deletion.
     */
    fun onStartDeletingSecret(secret: CredentialSecretMetadataDto)

    /** Closes the open dialog. */
    fun onCancelDialog()

    /**
     * @param update Transformation applied to the open profile form.
     */
    fun onUpdateProfileForm(update: (CredentialProfileFormState) -> CredentialProfileFormState)

    /**
     * @param update Transformation applied to the open secret form.
     */
    fun onUpdateSecretForm(update: (CredentialSecretFormState) -> CredentialSecretFormState)

    /** Saves the open profile form. */
    fun onSaveProfile()

    /** Saves the open secret form. */
    fun onSaveSecret()

    /** Deletes the row the confirmation dialog targets. */
    fun onConfirmDelete()

    /** Clears the dispatch warning. */
    fun onDismissDispatchWarning()
}
