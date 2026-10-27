package eu.torvian.chatbot.app.viewmodel.settings

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.NO_PROJECT_SECTION_TITLE
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.CredentialRepository
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.testutils.viewmodel.awaitLaunchedBy
import eu.torvian.chatbot.app.viewmodel.CredentialSecretFormState
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsDialog
import eu.torvian.chatbot.app.viewmodel.CredentialSettingsViewModel
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretLimits
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.project.ProjectDto
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/**
 * Verifies the credential settings surface rules: editor validation mirrors the shared rule set, a
 * stored secret is never displayed, the environment editor echoes a value verbatim, and the agent-role
 * links round-trip through the form.
 */
class CredentialSettingsViewModelTest {

    private val credentialRepository = mockk<CredentialRepository>(relaxed = true)
    private val workerRepository = mockk<WorkerRepository>(relaxed = true)
    private val agentRoleRepository = mockk<AgentRoleRepository>(relaxed = true)
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)

    private lateinit var rolesFlow: MutableStateFlow<DataState<RepositoryError, List<AgentRoleDto>>>
    private lateinit var projectsFlow: MutableStateFlow<DataState<RepositoryError, List<ProjectDto>>>
    private lateinit var viewModel: CredentialSettingsViewModel

    @BeforeTest
    fun setUp() {
        rolesFlow = MutableStateFlow(DataState.Success(emptyList()))
        projectsFlow = MutableStateFlow(DataState.Success(emptyList()))

        every { credentialRepository.profiles } returns
                MutableStateFlow(DataState.Success(emptyList<CredentialProfileDto>()))
        every { credentialRepository.secrets } returns
                MutableStateFlow(DataState.Success(emptyList<CredentialSecretMetadataDto>()))
        every { credentialRepository.dispatchWarning } returns MutableStateFlow(null)
        every { agentRoleRepository.roles } returns rolesFlow
        every { projectRepository.projects } returns projectsFlow
        coEvery { agentRoleRepository.loadRoles() } returns Either.Right(Unit)
        coEvery { projectRepository.loadProjects() } returns Either.Right(Unit)

        viewModel = CredentialSettingsViewModel(
            credentialRepository = credentialRepository,
            workerRepository = workerRepository,
            agentRoleRepository = agentRoleRepository,
            projectRepository = projectRepository,
            notificationService = notificationService,
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel the view model scope so the derived role-option stream stops with the test.
        viewModel.viewModelScope.cancel()
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
     * Ensures every owned role is offered under its project section, with the card label carrying the
     * project name and the no-project variant for unassociated roles.
     */
    @Test
    fun `role options are grouped by project and labelled with their project`() = runTest {
        withSubscription(viewModel.roleOptions) { awaitValue ->
            rolesFlow.value = DataState.Success(
                listOf(
                    role(id = 1L, name = "release", projectId = 10L),
                    role(id = 2L, name = "triage", projectId = null),
                )
            )
            projectsFlow.value = DataState.Success(listOf(project(id = 10L, name = "Payments")))

            // Await the state where the project label has resolved: the two streams emit separately, so a
            // size-only predicate could observe the roles before their project names arrived.
            val options = awaitValue { loaded ->
                loaded.labelsById.size == 2 && loaded.labelsById[1L] == "release — Payments"
            }

            assertEquals(
                mapOf(1L to "release — Payments", 2L to "triage — No project"),
                options.labelsById
            )
            assertEquals(listOf("Payments", NO_PROJECT_SECTION_TITLE), options.sections.map { it.title })
            assertEquals(listOf(1L), options.sections[0].roles.map { it.id })
            assertEquals(listOf(2L), options.sections[1].roles.map { it.id })
        }
    }

    /**
     * Ensures a role whose project no longer resolves keeps its card label with the no-project variant and
     * stays in the picker instead of disappearing.
     */
    @Test
    fun `a role with an unresolvable project keeps the no-project label`() = runTest {
        withSubscription(viewModel.roleOptions) { awaitValue ->
            rolesFlow.value = DataState.Success(listOf(role(id = 1L, name = "release", projectId = 10L)))
            projectsFlow.value = DataState.Success(emptyList())

            val options = awaitValue { it.labelsById.size == 1 }

            assertEquals("release — No project", options.labelsById.getValue(1L))
            assertEquals(NO_PROJECT_SECTION_TITLE, options.sections.single().title)
            assertEquals(listOf(1L), options.sections.single().roles.map { it.id })
        }
    }

    /**
     * Ensures the picker sections follow the project names ascending and keep the unassociated group last.
     */
    @Test
    fun `role options order project sections by name with no project last`() = runTest {
        withSubscription(viewModel.roleOptions) { awaitValue ->
            rolesFlow.value = DataState.Success(
                listOf(
                    role(id = 1L, name = "zeta-role", projectId = 30L),
                    role(id = 2L, name = "loose", projectId = null),
                    role(id = 3L, name = "alpha-role", projectId = 20L),
                )
            )
            projectsFlow.value = DataState.Success(
                listOf(
                    project(id = 30L, name = "Zeta"),
                    project(id = 20L, name = "Alpha"),
                )
            )

            // The two streams emit separately, so the sections are only final once the project names have
            // resolved; a size-only predicate could observe the roles while they are all still "No project".
            val options = awaitValue { loaded ->
                loaded.labelsById.size == 3 && loaded.labelsById[1L] == "zeta-role — Zeta"
            }

            assertEquals(
                listOf("Alpha", "Zeta", NO_PROJECT_SECTION_TITLE),
                options.sections.map { it.title }
            )
            assertEquals(listOf(3L, 1L, 2L), options.sections.flatMap { section -> section.roles.map { it.id } })
        }
    }

    /**
     * Ensures the editor pre-selects the roles a profile is already linked to.
     */
    @Test
    fun `startEditingProfile pre-fills the linked agent roles`() = runTest {
        viewModel.startEditingProfile(profile(agentRoleIds = setOf(4L, 9L)))

        val dialog = assertIs<CredentialSettingsDialog.Profile>(viewModel.dialog.value)
        assertEquals(setOf(4L, 9L), dialog.form.agentRoleIds)
    }

    /**
     * Ensures the selection reaches the signed create request unchanged.
     */
    @Test
    fun `saveProfile sends the selected agent roles`() = runTest {
        coEvery { credentialRepository.createProfile(any()) } returns Either.Right(profile())

        viewModel.startAddingProfile()
        fillValidProfile()
        viewModel.updateProfileForm { it.copy(agentRoleIds = setOf(4L, 9L)) }
        viewModel.viewModelScope.awaitLaunchedBy { viewModel.saveProfile() }

        coVerify(exactly = 1) { credentialRepository.createProfile(match { it.agentRoleIds == setOf(4L, 9L) }) }
    }

    /**
     * Ensures a profile with no linked role is still saved: it is a draft nobody may attach, not an
     * invalid form.
     */
    @Test
    fun `saveProfile accepts a draft without linked agent roles`() = runTest {
        coEvery { credentialRepository.createProfile(any()) } returns Either.Right(profile())

        viewModel.startAddingProfile()
        fillValidProfile()
        viewModel.viewModelScope.awaitLaunchedBy { viewModel.saveProfile() }

        coVerify(exactly = 1) { credentialRepository.createProfile(match { it.agentRoleIds.isEmpty() }) }
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
     * Keeps [flow] subscribed for the duration of [block], so the derived value stays computed and a
     * test can await the value it expects.
     *
     * @param flow Derived stream to observe.
     * @param block Assertions and interactions, with an awaiting helper for the expected value.
     */
    private suspend fun <T> withSubscription(
        flow: StateFlow<T>,
        block: suspend (awaitValue: suspend ((T) -> Boolean) -> T) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            flow.collect { /* keep the shared stream subscribed for the whole test */ }
        }
        try {
            block { predicate -> flow.first(predicate) }
        } finally {
            scope.cancel()
        }
    }

    /**
     * @param profile Linked role ids to carry.
     * @return A stored profile row with the defaults these tests do not vary.
     */
    private fun profile(agentRoleIds: Set<Long> = emptySet()): CredentialProfileDto = CredentialProfileDto(
        id = 1L,
        workerId = 17L,
        name = "vps",
        secretRef = "vps_password",
        command = "/bin/sh",
        channel = CredentialChannel.ASKPASS,
        askpassVariables = listOf("SSH_ASKPASS"),
        agentRoleIds = agentRoleIds,
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @param id Role identifier.
     * @param name Role machine name, used as the label.
     * @param projectId Project the role belongs to, or null when unassociated.
     * @return An agent role row carrying the fields the picker reads.
     */
    private fun role(id: Long, name: String, projectId: Long?): AgentRoleDto = AgentRoleDto(
        id = id,
        name = name,
        modelId = null,
        modelSettingsId = null,
        projectId = projectId,
    )

    /**
     * @param id Project identifier.
     * @param name Project name.
     * @return A project row carrying the fields the label composition reads.
     */
    private fun project(id: Long, name: String): ProjectDto = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochMilliseconds(0),
    )

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
