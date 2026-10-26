package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the credential settings category.
 *
 * Keeps the view-model wiring, the initial load and the breadcrumb update out of the presentational
 * tab, mirroring the other settings categories.
 *
 * @param viewModel Credential settings view model resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current page.
 */
@Composable
fun CredentialSettingsTabRoute(
    viewModel: CredentialSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {},
) {
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.cancelDialog()
        }
    }

    val breadcrumbs = listOf("Settings", SettingsCategory.Credentials.displayLabel)
    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    val workers by viewModel.workers.collectAsState()
    val selectedWorkerId by viewModel.selectedWorkerId.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val secrets by viewModel.secrets.collectAsState()
    val dialog by viewModel.dialog.collectAsState()
    val dispatchWarning by viewModel.dispatchWarning.collectAsState()
    val roleOptions by viewModel.roleOptions.collectAsState()

    CredentialSettingsTab(
        state = CredentialSettingsState(
            workers = workers,
            selectedWorkerId = selectedWorkerId,
            profiles = profiles,
            secrets = secrets,
            dialog = dialog,
            dispatchWarning = dispatchWarning,
            roleOptions = roleOptions,
        ),
        actions = object : CredentialSettingsActions {
            override fun onLoad() = viewModel.load()
            override fun onSelectWorker(workerId: Long?) = viewModel.selectWorker(workerId)
            override fun onStartAddingProfile() = viewModel.startAddingProfile()
            override fun onStartEditingProfile(
                profile: eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto,
            ) = viewModel.startEditingProfile(profile)

            override fun onStartDeletingProfile(
                profile: eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto,
            ) = viewModel.startDeletingProfile(profile)

            override fun onStartAddingSecret() = viewModel.startAddingSecret()
            override fun onStartReplacingSecret(
                secret: eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto,
            ) = viewModel.startReplacingSecret(secret)

            override fun onStartDeletingSecret(
                secret: eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto,
            ) = viewModel.startDeletingSecret(secret)

            override fun onCancelDialog() = viewModel.cancelDialog()
            override fun onUpdateProfileForm(
                update: (eu.torvian.chatbot.app.viewmodel.CredentialProfileFormState) ->
                eu.torvian.chatbot.app.viewmodel.CredentialProfileFormState,
            ) = viewModel.updateProfileForm(update)

            override fun onUpdateSecretForm(
                update: (eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState) ->
                eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState,
            ) = viewModel.updateSecretForm(update)

            override fun onSaveProfile() = viewModel.saveProfile()
            override fun onSaveSecret() = viewModel.saveSecret()
            override fun onConfirmDelete() = viewModel.confirmDelete()
            override fun onDismissDispatchWarning() = viewModel.dismissDispatchWarning()
        },
        modifier = modifier,
    )
}
