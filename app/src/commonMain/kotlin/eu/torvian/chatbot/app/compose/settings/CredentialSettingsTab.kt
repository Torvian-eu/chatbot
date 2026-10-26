package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.CredentialDispatchWarning
import eu.torvian.chatbot.app.viewmodel.CredentialProfileFormState
import eu.torvian.chatbot.app.viewmodel.CredentialRoleOptions
import eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsDialog
import eu.torvian.chatbot.common.models.api.credential.*
import eu.torvian.chatbot.common.models.worker.WorkerDto

/** Marker shown on a profile that no agent role is linked to. */
private const val UNLINKED_PROFILE_MARKER = "No agent roles linked — unusable until linked"

/**
 * Credential profiles and secrets, grouped by worker.
 *
 * The surface never displays a stored secret value: only its reference and last update time are shown,
 * and editing offers replacement. A save whose push did not reach the worker keeps the row and raises a
 * dismissible warning instead of reporting failure.
 *
 * @param state Current surface state.
 * @param actions Callbacks forwarded to the view model.
 * @param modifier Modifier applied to the whole surface.
 */
@Composable
fun CredentialSettingsTab(
    state: CredentialSettingsState,
    actions: CredentialSettingsActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val workersState = state.workers) {
            is DataState.Loading -> LoadingStateDisplay(
                message = "Loading workers...",
                modifier = Modifier.fillMaxSize()
            )

            is DataState.Error -> ErrorStateDisplay(
                title = "Failed to load workers",
                error = workersState.error,
                onRetry = { actions.onLoad() },
                modifier = Modifier.fillMaxSize()
            )

            is DataState.Success -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Credential profiles let a terminal session run one fixed command as a separate " +
                            "account without exposing the secret to the model or to other sessions.",
                    style = MaterialTheme.typography.bodyMedium
                )

                WorkerPicker(
                    workers = workersState.data,
                    selectedWorkerId = state.selectedWorkerId,
                    onSelect = actions::onSelectWorker
                )

                if (state.dispatchWarning != null) {
                    DispatchWarningCard(state.dispatchWarning, actions::onDismissDispatchWarning)
                }

                if (state.selectedWorkerId == null) {
                    Text(
                        text = "Select a worker to manage its credential profiles and secrets.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    ProfilesSection(state, actions)
                    SecretsSection(state, actions)
                }

                if (state.selectedWorkerId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions::onStartAddingProfile) { Text("New profile") }
                        Button(onClick = actions::onStartAddingSecret) { Text("New secret") }
                    }
                }
            }

            is DataState.Idle -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = actions::onLoad) { Text("Load credential settings") }
            }
        }

        when (val dialog = state.dialog) {
            is CredentialSettingsDialog.None -> Unit
            is CredentialSettingsDialog.Profile -> CredentialProfileDialog(
                form = dialog.form,
                roleOptions = state.roleOptions,
                onUpdate = actions::onUpdateProfileForm,
                onSave = actions::onSaveProfile,
                onDismiss = actions::onCancelDialog
            )

            is CredentialSettingsDialog.Secret -> CredentialSecretDialog(
                form = dialog.form,
                onUpdate = actions::onUpdateSecretForm,
                onSave = actions::onSaveSecret,
                onDismiss = actions::onCancelDialog
            )

            is CredentialSettingsDialog.ConfirmDelete -> AlertDialog(
                onDismissRequest = actions::onCancelDialog,
                title = { Text("Delete ${dialog.label}?") },
                text = {
                    Text(
                        "The stored metadata is removed. A running terminal session that is already " +
                                "attached keeps running; the next attach fails."
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions::onConfirmDelete) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = actions::onCancelDialog) { Text("Cancel") }
                }
            )
        }
    }
}

/**
 * Worker selector, which scopes every read and write on the surface.
 *
 * @param workers Workers the operator owns.
 * @param selectedWorkerId Currently selected worker, if any.
 * @param onSelect Callback receiving the chosen worker id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerPicker(
    workers: List<WorkerDto>,
    selectedWorkerId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = workers.firstOrNull { it.id == selectedWorkerId }

    Column {
        Text(text = "Worker", style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected?.displayName ?: "Select a worker",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                workers.forEach { worker ->
                    DropdownMenuItem(
                        text = { Text(worker.displayName) },
                        onClick = {
                            expanded = false
                            onSelect(worker.id)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Renders the persisted-but-not-delivered warning for one save.
 *
 * @param warning Warning raised by the repository.
 * @param onDismiss Callback that clears it.
 */
@Composable
private fun DispatchWarningCard(warning: CredentialDispatchWarning, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Saved, but worker ${warning.workerId} has not received ${warning.label} yet",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = warning.failure.explain(), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * Explains a delivery failure in operator terms without any worker-supplied text.
 *
 * @receiver Classified delivery failure.
 * @return One sentence describing what the operator should expect.
 */
private fun WorkerDispatchFailure.explain(): String = when (this) {
    WorkerDispatchFailure.WORKER_OFFLINE ->
        "The worker is offline. The change is stored and will sync when it reconnects."

    WorkerDispatchFailure.WORKER_TIMEOUT ->
        "The worker did not confirm in time. The change is stored and will sync on the next reconnect."

    WorkerDispatchFailure.WORKER_DISCONNECTED ->
        "The worker disconnected while receiving the change. It will sync on the next reconnect."

    WorkerDispatchFailure.WORKER_REJECTED ->
        "The worker refused the change, which usually means its version does not support credential " +
                "commands yet. Update the worker; the change will sync afterwards."

    WorkerDispatchFailure.TRANSPORT_FAILED ->
        "The change could not be sent. It is stored and will sync on the next reconnect."

    WorkerDispatchFailure.PROTOCOL_ERROR ->
        "The delivery outcome could not be read. The change is stored and will sync on the next reconnect."
}

/**
 * Lists the profiles of the selected worker.
 *
 * @param state Surface state.
 * @param actions Callbacks forwarded to the view model.
 */
@Composable
private fun ProfilesSection(state: CredentialSettingsState, actions: CredentialSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Profiles", style = MaterialTheme.typography.titleMedium)
        when (val profiles = state.profiles) {
            is DataState.Success -> if (profiles.data.isEmpty()) {
                Text(text = "No profiles yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                profiles.data.forEach { profile ->
                    ProfileCard(profile, state.roleOptions, actions)
                }
            }

            is DataState.Error -> Text(
                text = profiles.error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            else -> LoadingStateDisplay(message = "Loading profiles...")
        }
    }
}

/**
 * Lists the secret metadata of the selected worker.
 *
 * @param state Surface state.
 * @param actions Callbacks forwarded to the view model.
 */
@Composable
private fun SecretsSection(state: CredentialSettingsState, actions: CredentialSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Secrets", style = MaterialTheme.typography.titleMedium)
        when (val secrets = state.secrets) {
            is DataState.Success -> if (secrets.data.isEmpty()) {
                Text(text = "No secrets yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                secrets.data.forEach { secret ->
                    SecretCard(secret, actions)
                }
            }

            is DataState.Error -> Text(
                text = secrets.error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            else -> LoadingStateDisplay(message = "Loading secrets...")
        }
    }
}

/**
 * One profile row.
 *
 * The linked agent roles are resolved through the same derivation the editor reads, so a renamed role
 * shows up without reopening the dialog. Unlike the picker chips, these labels keep the project name:
 * a card row has no section header to carry it. A profile with no links carries the
 * unusable-until-linked marker: it is still listed and relayed, but no role may attach it.
 *
 * @param profile Profile to render.
 * @param roleOptions Roles and their card labels, used to resolve the labels of the linked ids.
 * @param actions Callbacks forwarded to the view model.
 */
@Composable
private fun ProfileCard(
    profile: CredentialProfileDto,
    roleOptions: CredentialRoleOptions,
    actions: CredentialSettingsActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = profile.name, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = profile.channel.name.lowercase(), style = MaterialTheme.typography.labelMedium)
                if (!profile.isEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "disabled", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = "secret: ${profile.secretRef} · command: ${profile.command}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            val description = profile.description
            if (!description.isNullOrBlank()) {
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            // The marker keys off the id set, not the resolved labels, so a link to a since-deleted role
            // still counts as linked rather than silently turning the profile into a draft.
            if (profile.agentRoleIds.isEmpty()) {
                Text(
                    text = UNLINKED_PROFILE_MARKER,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val labels = profile.agentRoleIds.mapNotNull { id -> roleOptions.labelsById[id] }
                if (labels.isNotEmpty()) {
                    Text(
                        text = "agent roles: ${labels.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row {
                TextButton(onClick = { actions.onStartEditingProfile(profile) }) { Text("Edit") }
                TextButton(onClick = { actions.onStartDeletingProfile(profile) }) { Text("Delete") }
            }
        }
    }
}

/**
 * One secret row: reference and timestamps only, never the value or a masked prefix of it.
 *
 * @param secret Secret metadata to render.
 * @param actions Callbacks forwarded to the view model.
 */
@Composable
private fun SecretCard(secret: CredentialSecretMetadataDto, actions: CredentialSettingsActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = secret.reference, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "updated ${secret.updatedAt} · certificate ${secret.workerCertificateFingerprint}",
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(onClick = { actions.onStartReplacingSecret(secret) }) { Text("Replace value") }
                TextButton(onClick = { actions.onStartDeletingSecret(secret) }) { Text("Delete") }
            }
        }
    }
}

/**
 * Profile editor.
 *
 * @param form Editable form state.
 * @param roleOptions Roles the profile may be linked to, grouped by project for the picker.
 * @param onUpdate Applies a transformation to the form.
 * @param onSave Saves the form.
 * @param onDismiss Closes the dialog.
 */
@Composable
private fun CredentialProfileDialog(
    form: CredentialProfileFormState,
    roleOptions: CredentialRoleOptions,
    onUpdate: ((CredentialProfileFormState) -> CredentialProfileFormState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.profileId == null) "New credential profile" else "Edit credential profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { value -> onUpdate { it.copy(name = value) } },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { value -> onUpdate { it.copy(description = value) } },
                    label = { Text("Description shown to the model") }
                )
                OutlinedTextField(
                    value = form.secretRef,
                    onValueChange = { value -> onUpdate { it.copy(secretRef = value) } },
                    label = { Text("Secret reference") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.command,
                    onValueChange = { value -> onUpdate { it.copy(command = value) } },
                    label = { Text("Command (single executable)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.argsText,
                    onValueChange = { value -> onUpdate { it.copy(argsText = value) } },
                    label = { Text("Arguments, one per line") }
                )
                ChannelPicker(form.channel) { channel -> onUpdate { it.copy(channel = channel) } }
                when (form.channel) {
                    CredentialChannel.ASKPASS -> {
                        OutlinedTextField(
                            value = form.askpassVariablesText,
                            onValueChange = { value -> onUpdate { it.copy(askpassVariablesText = value) } },
                            label = { Text("Askpass variables, one per line (e.g. SSH_ASKPASS)") }
                        )
                        OutlinedTextField(
                            value = form.askpassUsername,
                            onValueChange = { value -> onUpdate { it.copy(askpassUsername = value) } },
                            label = { Text("Username answered for username prompts") },
                            singleLine = true
                        )
                        Text(
                            text = "OpenSSH also needs SSH_ASKPASS_REQUIRE=force and DISPLAY in the " +
                                    "environment below, otherwise it prompts on the terminal instead.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    CredentialChannel.ENV -> OutlinedTextField(
                        value = form.envName,
                        onValueChange = { value -> onUpdate { it.copy(envName = value) } },
                        label = { Text("Environment variable receiving the value") },
                        singleLine = true
                    )

                    CredentialChannel.FILE -> {
                        OutlinedTextField(
                            value = form.envName,
                            onValueChange = { value -> onUpdate { it.copy(envName = value) } },
                            label = { Text("Environment variable receiving the file path") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = form.fileName,
                            onValueChange = { value -> onUpdate { it.copy(fileName = value) } },
                            label = { Text("Secret file name") },
                            singleLine = true
                        )
                    }
                }
                OutlinedTextField(
                    value = form.environmentText,
                    onValueChange = { value -> onUpdate { it.copy(environmentText = value) } },
                    label = { Text("Non-secret environment, one NAME=value per line") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.isEnabled,
                        onCheckedChange = { checked -> onUpdate { it.copy(isEnabled = checked) } }
                    )
                    Text(text = "Enabled")
                }
                HorizontalDivider()
                Text(text = "Agent roles allowed to attach", style = MaterialTheme.typography.titleSmall)
                // Every owned role is offered, across all projects, because a profile may be shared by
                // roles of different projects; the section header carries the project, so same-named
                // roles stay distinguishable without repeating it on every chip.
                AgentRoleChipPicker(
                    sections = roleOptions.sections,
                    selectedRoleIds = form.agentRoleIds,
                    onToggleRole = { roleId ->
                        onUpdate { current ->
                            current.copy(
                                agentRoleIds = if (roleId in current.agentRoleIds) {
                                    current.agentRoleIds - roleId
                                } else {
                                    current.agentRoleIds + roleId
                                }
                            )
                        }
                    }
                )
                if (roleOptions.sections.isEmpty()) {
                    Text(
                        text = "No agent roles are available to link.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "A profile with no linked role cannot be attached by any role.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (form.violations.isNotEmpty()) {
                    HorizontalDivider()
                    form.violations.forEach { violation ->
                        Text(
                            text = violation,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Channel selector.
 *
 * @param selected Currently selected channel.
 * @param onSelect Callback receiving the chosen channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPicker(selected: CredentialChannel, onSelect: (CredentialChannel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected.name.lowercase(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Delivery channel") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CredentialChannel.entries.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(channel.name.lowercase()) },
                    onClick = {
                        expanded = false
                        onSelect(channel)
                    }
                )
            }
        }
    }
}

/**
 * Secret editor: replacement only, because a stored value is never returned.
 *
 * The value field is masked by default and can be revealed only for the text being typed here;
 * stored values are never read back. It accepts multiple lines, because private keys, certificates
 * and service-account JSON documents only look complete when their line structure is visible.
 *
 * @param form Editable form state.
 * @param onUpdate Applies a transformation to the form.
 * @param onSave Saves the form.
 * @param onDismiss Closes the dialog.
 */
@Composable
private fun CredentialSecretDialog(
    form: CredentialSecretFormState,
    onUpdate: ((CredentialSecretFormState) -> CredentialSecretFormState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the dialog target so a reveal never carries over to another secret or to the other flow.
    var isValueVisible by remember(form.secretId, form.isReplace) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.isReplace) "Replace secret value" else "New credential secret") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.reference,
                    onValueChange = { value -> onUpdate { it.copy(reference = value) } },
                    label = { Text("Reference (used by a profile)") },
                    singleLine = true,
                    enabled = !form.isReplace
                )
                OutlinedTextField(
                    value = form.value,
                    onValueChange = { value -> onUpdate { it.copy(value = value) } },
                    label = { Text("Value (stored encrypted, never shown again)") },
                    minLines = 1,
                    maxLines = 8,
                    visualTransformation = if (isValueVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isValueVisible = !isValueVisible }) {
                            Icon(
                                imageVector = if (isValueVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (isValueVisible) "Hide secret value" else "Show secret value",
                            )
                        }
                    },
                )
                Text(
                    text = "Multi-line values are kept exactly as entered. Limit " +
                            "${CredentialSecretLimits.MAX_SECRET_BYTES} bytes. The server stores " +
                            "ciphertext that only the bound worker can decrypt.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (form.violations.isNotEmpty()) {
                    HorizontalDivider()
                    form.violations.forEach { violation ->
                        Text(
                            text = violation,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
