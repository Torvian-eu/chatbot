package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.CredentialDispatchWarning
import eu.torvian.chatbot.app.repository.CredentialRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.credential.*
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the credential settings surface.
 *
 * The view model owns the selected worker, the two read models, the editor dialogs and the delivery
 * warning. Validation mirrors the shared rule set so an operator sees a reserved name or a
 * contradictory channel field before the server rejects it; the server and the worker apply the same
 * rules again.
 *
 * @property credentialRepository Repository holding profiles, secrets and the dispatch warning.
 * @property workerRepository Repository providing the worker picker.
 * @property notificationService Sink for operator-visible notifications.
 */
class CredentialSettingsViewModel(
    private val credentialRepository: CredentialRepository,
    private val workerRepository: WorkerRepository,
    private val notificationService: NotificationService,
) : ViewModel() {

    /** Profiles loaded for the selected worker. */
    val profiles: StateFlow<DataState<RepositoryError, List<CredentialProfileDto>>> =
        credentialRepository.profiles

    /** Secret metadata loaded for the selected worker. */
    val secrets: StateFlow<DataState<RepositoryError, List<CredentialSecretMetadataDto>>> =
        credentialRepository.secrets

    /** Warning raised by the last write that was persisted but not delivered. */
    val dispatchWarning: StateFlow<CredentialDispatchWarning?> = credentialRepository.dispatchWarning

    private val _workers = MutableStateFlow<DataState<RepositoryError, List<WorkerDto>>>(DataState.Idle)

    /** Workers the operator may bind a profile or secret to. */
    val workers: StateFlow<DataState<RepositoryError, List<WorkerDto>>> = _workers.asStateFlow()

    private val _selectedWorkerId = MutableStateFlow<Long?>(null)

    /** Worker currently shown in the surface, or null before the first worker is chosen. */
    val selectedWorkerId: StateFlow<Long?> = _selectedWorkerId.asStateFlow()

    private val _dialog = MutableStateFlow<CredentialSettingsDialog>(CredentialSettingsDialog.None)

    /** Dialog currently open, if any. */
    val dialog: StateFlow<CredentialSettingsDialog> = _dialog.asStateFlow()

    /**
     * Loads the worker picker and, once a worker is known, its profiles and secrets.
     */
    fun load() {
        viewModelScope.launch {
            _workers.update { DataState.Loading }
            workerRepository.loadWorkers().fold(
                ifLeft = { error ->
                    _workers.update { DataState.Error(error) }
                    notificationService.repositoryError(error, "Failed to load workers")
                },
                ifRight = {
                    val loaded = (workerRepository.workers.value as? DataState.Success)?.data.orEmpty()
                    _workers.update { DataState.Success(loaded) }
                    if (_selectedWorkerId.value == null) {
                        selectWorker(loaded.firstOrNull()?.id)
                    }
                }
            )
        }
    }

    /**
     * Switches the surface to [workerId] and reloads both read models.
     *
     * @param workerId Worker to show, or null to clear the selection.
     */
    fun selectWorker(workerId: Long?) {
        _selectedWorkerId.value = workerId
        if (workerId == null) return
        viewModelScope.launch {
            credentialRepository.loadProfiles(workerId).onLeft { error ->
                notificationService.repositoryError(error, "Failed to load credential profiles")
            }
            credentialRepository.loadSecrets(workerId).onLeft { error ->
                notificationService.repositoryError(error, "Failed to load credential secrets")
            }
        }
    }

    /**
     * Opens the profile editor for a new profile bound to the selected worker.
     */
    fun startAddingProfile() {
        _dialog.value = CredentialSettingsDialog.Profile(
            CredentialProfileFormState(workerId = _selectedWorkerId.value)
        )
    }

    /**
     * Opens the profile editor pre-filled from [profile].
     *
     * @param profile Profile to edit.
     */
    fun startEditingProfile(profile: CredentialProfileDto) {
        _dialog.value = CredentialSettingsDialog.Profile(
            CredentialProfileFormState(
                profileId = profile.id,
                workerId = profile.workerId,
                name = profile.name,
                description = profile.description.orEmpty(),
                secretRef = profile.secretRef,
                command = profile.command,
                argsText = profile.args.joinToString("\n"),
                channel = profile.channel,
                envName = profile.envName.orEmpty(),
                fileName = profile.fileName.orEmpty(),
                askpassUsername = profile.askpassUsername.orEmpty(),
                askpassVariablesText = profile.askpassVariables.joinToString("\n"),
                environmentText = profile.environmentVariables.entries.joinToString("\n") { entry ->
                    "${entry.key}=${entry.value}"
                },
                isEnabled = profile.isEnabled,
            )
        )
    }

    /**
     * Opens the secret editor for a new secret bound to the selected worker.
     */
    fun startAddingSecret() {
        _dialog.value = CredentialSettingsDialog.Secret(
            CredentialSecretFormState(workerId = _selectedWorkerId.value)
        )
    }

    /**
     * Opens the secret editor in replace mode: the stored value is never shown.
     *
     * @param secret Metadata of the secret whose value will be replaced.
     */
    fun startReplacingSecret(secret: CredentialSecretMetadataDto) {
        _dialog.value = CredentialSettingsDialog.Secret(
            CredentialSecretFormState(
                secretId = secret.id,
                workerId = secret.workerId,
                reference = secret.reference,
                isReplace = true,
            )
        )
    }

    /**
     * @param profile Profile awaiting deletion.
     */
    fun startDeletingProfile(profile: CredentialProfileDto) {
        _dialog.value = CredentialSettingsDialog.ConfirmDelete(
            isSecret = false,
            id = profile.id,
            label = profile.name
        )
    }

    /**
     * @param secret Secret awaiting deletion.
     */
    fun startDeletingSecret(secret: CredentialSecretMetadataDto) {
        _dialog.value = CredentialSettingsDialog.ConfirmDelete(
            isSecret = true,
            id = secret.id,
            label = secret.reference
        )
    }

    /** Closes the open dialog. */
    fun cancelDialog() {
        _dialog.value = CredentialSettingsDialog.None
    }

    /**
     * Applies [update] to the open profile form.
     *
     * @param update Pure transformation of the current form.
     */
    fun updateProfileForm(update: (CredentialProfileFormState) -> CredentialProfileFormState) {
        val current = _dialog.value as? CredentialSettingsDialog.Profile ?: return
        _dialog.value = current.copy(form = update(current.form))
    }

    /**
     * Applies [update] to the open secret form.
     *
     * @param update Pure transformation of the current form.
     */
    fun updateSecretForm(update: (CredentialSecretFormState) -> CredentialSecretFormState) {
        val current = _dialog.value as? CredentialSettingsDialog.Secret ?: return
        _dialog.value = current.copy(form = update(current.form))
    }

    /**
     * Validates and saves the open profile form.
     *
     * The write is kept even when the worker has not received it yet; the warning stream carries that
     * state to the UI and nothing is rolled back.
     */
    fun saveProfile() {
        val form = (_dialog.value as? CredentialSettingsDialog.Profile)?.form ?: return
        val fields = form.toFields() ?: return rejectProfile(form, listOf("Select a worker first"))
        val violations = CredentialProfileRules.validate(fields)
        if (violations.isNotEmpty()) {
            rejectProfile(form, violations)
            return
        }

        viewModelScope.launch {
            val result = if (form.profileId == null) {
                credentialRepository.createProfile(fields.toCreateRequest())
            } else {
                credentialRepository.updateProfile(form.profileId, fields.toUpdateRequest())
            }
            result.fold(
                ifLeft = { error ->
                    rejectProfile(form, listOf(error.message))
                    notificationService.repositoryError(error, "Failed to save credential profile")
                },
                ifRight = {
                    _dialog.value = CredentialSettingsDialog.None
                    notificationService.genericSuccess("Credential profile saved")
                }
            )
        }
    }

    /**
     * Validates and saves the open secret form.
     *
     * The plaintext is present only in the request this method builds; it is not stored in the read
     * model and the form is cleared as soon as the dialog closes.
     */
    fun saveSecret() {
        val form = (_dialog.value as? CredentialSettingsDialog.Secret)?.form ?: return
        val workerId = form.workerId
        if (workerId == null) {
            rejectSecret(form, listOf("Select a worker first"))
            return
        }
        val violations = validateSecret(form)
        if (violations.isNotEmpty()) {
            rejectSecret(form, violations)
            return
        }

        viewModelScope.launch {
            val result = if (form.secretId == null) {
                credentialRepository.createSecret(
                    CreateCredentialSecretRequest(
                        workerId = workerId,
                        reference = form.reference.trim(),
                        value = form.value,
                    )
                )
            } else {
                credentialRepository.updateSecret(
                    form.secretId,
                    UpdateCredentialSecretRequest(reference = form.reference.trim(), value = form.value)
                )
            }
            result.fold(
                ifLeft = { error ->
                    rejectSecret(form, listOf(error.message))
                    notificationService.repositoryError(error, "Failed to save credential secret")
                },
                ifRight = {
                    _dialog.value = CredentialSettingsDialog.None
                    notificationService.genericSuccess("Credential secret saved")
                }
            )
        }
    }

    /** Deletes the row the confirmation dialog currently targets. */
    fun confirmDelete() {
        val target = _dialog.value as? CredentialSettingsDialog.ConfirmDelete ?: return
        viewModelScope.launch {
            val result = if (target.isSecret) {
                credentialRepository.deleteSecret(target.id)
            } else {
                credentialRepository.deleteProfile(target.id)
            }
            result.fold(
                ifLeft = { error ->
                    notificationService.repositoryError(error, "Failed to delete ${target.label}")
                },
                ifRight = {
                    _dialog.value = CredentialSettingsDialog.None
                }
            )
        }
    }

    /** Clears the dispatch warning, typically after it has been shown. */
    fun dismissDispatchWarning() {
        credentialRepository.dismissDispatchWarning()
    }

    /**
     * Validates one secret form against the shared rules that need no profile context.
     *
     * @param form Form being validated.
     * @return Violations, without echoing the value.
     */
    private fun validateSecret(form: CredentialSecretFormState): List<String> {
        val violations = mutableListOf<String>()
        // The reference is compared and stored trimmed, so the shared rule sees the stored form.
        CredentialProfileRules.validateReference(form.reference.trim())?.let { violations += it }
        CredentialSecretLimits.validateValue(form.value)?.let { violations += it }
        val size = form.value.encodeToByteArray().size
        if (size > CredentialSecretLimits.MAX_SECRET_BYTES) {
            violations += "value must be at most ${CredentialSecretLimits.MAX_SECRET_BYTES} bytes"
        }
        return violations
    }

    /**
     * Shows profile violations in the open dialog without closing it.
     *
     * @param form Form that failed validation.
     * @param violations Messages to display.
     */
    private fun rejectProfile(form: CredentialProfileFormState, violations: List<String>) {
        _dialog.value = CredentialSettingsDialog.Profile(form.copy(violations = violations))
    }

    /**
     * Shows secret violations in the open dialog without closing it.
     *
     * @param form Form that failed validation.
     * @param violations Messages to display.
     */
    private fun rejectSecret(form: CredentialSecretFormState, violations: List<String>) {
        _dialog.value = CredentialSettingsDialog.Secret(form.copy(violations = violations))
    }

    /**
     * Converts the editor state into the signed field set.
     *
     * @return The field set, or null when no worker is selected.
     */
    private fun CredentialProfileFormState.toFields(): CredentialProfileFields? {
        val worker = workerId ?: return null
        return CredentialProfileFields(
            workerId = worker,
            name = name.trim(),
            description = description.trim().ifBlank { null },
            secretRef = secretRef.trim(),
            command = command.trim(),
            args = argsText.asLines(),
            channel = channel,
            envName = envName.trim().ifBlank { null },
            fileName = fileName.trim().ifBlank { null },
            askpassUsername = askpassUsername.trim().ifBlank { null },
            isEnabled = isEnabled,
            environmentVariables = environmentText.asEnvironmentMap(),
            askpassVariables = askpassVariablesText.asLines(),
        )
    }

    /**
     * @receiver Field set to create.
     * @return Create request carrying the same fields.
     */
    private fun CredentialProfileFields.toCreateRequest(): CreateCredentialProfileRequest =
        CreateCredentialProfileRequest(
            workerId = workerId,
            name = name,
            description = description,
            secretRef = secretRef,
            command = command,
            args = args,
            channel = channel,
            envName = envName,
            fileName = fileName,
            askpassUsername = askpassUsername,
            isEnabled = isEnabled,
            environmentVariables = environmentVariables,
            askpassVariables = askpassVariables,
        )

    /**
     * @receiver Field set to write.
     * @return Update request carrying the same fields.
     */
    private fun CredentialProfileFields.toUpdateRequest(): UpdateCredentialProfileRequest =
        UpdateCredentialProfileRequest(
            workerId = workerId,
            name = name,
            description = description,
            secretRef = secretRef,
            command = command,
            args = args,
            channel = channel,
            envName = envName,
            fileName = fileName,
            askpassUsername = askpassUsername,
            isEnabled = isEnabled,
            environmentVariables = environmentVariables,
            askpassVariables = askpassVariables,
        )

    /**
     * @receiver Multiline text with one item per line.
     * @return Non-blank trimmed lines.
     */
    private fun String.asLines(): List<String> =
        split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * @receiver Multiline text with one `NAME=value` pair per line.
     * @return The parsed map; a line without `=` is kept with an empty value so the operator sees it.
     */
    private fun String.asEnvironmentMap(): Map<String, String> =
        split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) line to "" else line.substring(0, separator).trim() to line.substring(separator + 1)
            }
}
