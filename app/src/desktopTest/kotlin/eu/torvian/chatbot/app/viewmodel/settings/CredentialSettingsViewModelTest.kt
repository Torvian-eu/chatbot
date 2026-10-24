package eu.torvian.chatbot.app.viewmodel.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.CredentialRepository
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsDialog
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsViewModel
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretLimits
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/**
 * Verifies the credential settings surface rules: editor validation mirrors the shared rule set, a
 * stored secret is never displayed, and the environment editor echoes a value verbatim.
 */
class CredentialSettingsViewModelTest {

    private val credentialRepository = mockk<CredentialRepository>(relaxed = true)
    private val workerRepository = mockk<WorkerRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)

    private val viewModel = CredentialSettingsViewModel(
        credentialRepository = credentialRepository,
        workerRepository = workerRepository,
        notificationService = notificationService,
    )

    @BeforeTest
    fun setUp() {
        every { credentialRepository.profiles } returns
                MutableStateFlow(DataState.Success(emptyList<CredentialProfileDto>()))
        every { credentialRepository.secrets } returns
                MutableStateFlow(DataState.Success(emptyList<CredentialSecretMetadataDto>()))
        every { credentialRepository.dispatchWarning } returns MutableStateFlow(null)
    }

    /**
     * Ensures a reserved environment name is refused in the dialog before any request is sent.
     */
    @Test
    fun `saveProfile refuses a reserved environment name`() = runTest {
        viewModel.startAddingProfile()
        fillValidProfile()
        viewModel.updateProfileForm { it.copy(environmentText = "HOME=/root") }
        viewModel.saveProfile()

        val dialog = assertIs<CredentialSettingsDialog.Profile>(viewModel.dialog.value)
        assertTrue(dialog.form.violations.any { "reserved" in it }, "expected a reserved-name violation")
        coVerify(exactly = 0) { credentialRepository.createProfile(any()) }
    }

    /**
     * Ensures a non-secret environment value round-trips verbatim through the editor.
     */
    @Test
    fun `environment editor echoes a value verbatim`() = runTest {
        viewModel.startAddingProfile()
        viewModel.updateProfileForm { it.copy(environmentText = "SSH_ASKPASS_REQUIRE=force") }

        val dialog = assertIs<CredentialSettingsDialog.Profile>(viewModel.dialog.value)
        assertEquals("SSH_ASKPASS_REQUIRE=force", dialog.form.environmentText)
    }

    /**
     * Ensures an askpass profile without variable names is refused.
     */
    @Test
    fun `saveProfile refuses an askpass profile without askpass variables`() = runTest {
        viewModel.startAddingProfile()
        fillValidProfile()
        viewModel.updateProfileForm { it.copy(askpassVariablesText = "") }
        viewModel.saveProfile()

        val dialog = assertIs<CredentialSettingsDialog.Profile>(viewModel.dialog.value)
        assertTrue(dialog.form.violations.any { "askpassVariables" in it })
        coVerify(exactly = 0) { credentialRepository.createProfile(any()) }
    }

    /**
     * Ensures a secret replacement never pre-fills the stored value.
     */
    @Test
    fun `replace mode does not expose the stored value`() = runTest {
        viewModel.startReplacingSecret(secretMetadata())

        val dialog = assertIs<CredentialSettingsDialog.Secret>(viewModel.dialog.value)
        assertTrue(dialog.form.isReplace)
        assertEquals("", dialog.form.value)
        assertEquals("ref", dialog.form.reference)
    }

    /**
     * Ensures an oversized secret value is refused before the request is made.
     */
    @Test
    fun `saveSecret refuses an oversized value`() = runTest {
        viewModel.startAddingSecret()
        viewModel.updateSecretForm {
            CredentialSecretFormState(
                workerId = 17L,
                reference = "ref",
                value = "x".repeat(CredentialSecretLimits.MAX_SECRET_BYTES + 1)
            )
        }
        viewModel.saveSecret()

        val dialog = assertIs<CredentialSettingsDialog.Secret>(viewModel.dialog.value)
        assertTrue(dialog.form.violations.any { "at most" in it })
        coVerify(exactly = 0) { credentialRepository.createSecret(any()) }
    }

    /**
     * Ensures a reference the shared rule refuses is caught in the dialog.
     */
    @Test
    fun `saveSecret refuses a reference with control characters`() = runTest {
        viewModel.startAddingSecret()
        viewModel.updateSecretForm {
            CredentialSecretFormState(workerId = 17L, reference = "vps\npassword", value = "value")
        }
        viewModel.saveSecret()

        val dialog = assertIs<CredentialSettingsDialog.Secret>(viewModel.dialog.value)
        assertTrue(dialog.form.violations.any { "control characters" in it })
        coVerify(exactly = 0) { credentialRepository.createSecret(any()) }
    }

    /**
     * Fills the open profile form with values that pass every rule but the one under test.
     */
    private fun fillValidProfile() {
        viewModel.updateProfileForm {
            it.copy(
                workerId = 17L,
                name = "vps",
                secretRef = "vps_password",
                command = "/bin/sh",
                askpassVariablesText = "SSH_ASKPASS",
                channel = CredentialChannel.ASKPASS,
            )
        }
    }

    /**
     * @return Metadata of a stored secret, with no value field.
     */
    private fun secretMetadata(): CredentialSecretMetadataDto = CredentialSecretMetadataDto(
        id = 9L,
        workerId = 17L,
        reference = "ref",
        algorithm = "RSA-OAEP-256+A256GCM",
        workerCertificateFingerprint = "fp",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
