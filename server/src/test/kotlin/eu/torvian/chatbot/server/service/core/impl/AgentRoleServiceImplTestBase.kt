package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.llm.CompletionModelSettings
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Shared fixtures and service setup for the [AgentRoleServiceImpl] concern test suites.
 *
 * Provides the mocked DAOs, the default model-preset/settings fixtures and the fresh per-test
 * service instance. JUnit runs the inherited setup/teardown hooks for every subclass, so per-test
 * isolation matches the original single-class suite. Preset, tool, instruction, ownership, disabled
 * state, project-membership and scope-rule behavior are exercised by dedicated subclasses.
 */
abstract class AgentRoleServiceImplTestBase {

    /** Mocked role-row DAO backing role reads and writes. */
    protected lateinit var agentRoleDao: AgentRoleDao
    /** Mocked join-table DAO backing the role's tool set. */
    protected lateinit var agentRoleToolDao: AgentRoleToolDao
    /** Mocked ownership DAO backing ownership checks. */
    protected lateinit var agentRoleOwnershipDao: AgentRoleOwnershipDao
    /** Mocked spawn allow-list DAO backing spawn-target reads and writes. */
    protected lateinit var agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao
    /** Mocked side-table DAO backing the per-user disabled state. */
    protected lateinit var agentRoleDisabledDao: AgentRoleDisabledDao
    /** Mocked project DAO backing project membership validation. */
    protected lateinit var projectDao: ProjectDao
    /** Mocked session DAO backing the role-update legality sweep. */
    protected lateinit var sessionDao: SessionDao
    /** Mocked preset DAO backing preset validation and resolution. */
    protected lateinit var modelPresetDao: ModelPresetDao
    /** Mocked settings DAO backing preset settings validation. */
    protected lateinit var settingsDao: SettingsDao
    /** Mocked tool DAO backing tool ownership validation. */
    protected lateinit var toolDefinitionDao: ToolDefinitionDao
    /** Mocked transaction scope executing blocks inline as a pass-through. */
    protected lateinit var transactionScope: TransactionScope
    /** Service under test, recreated before each test with the mocked DAOs. */
    protected lateinit var service: AgentRoleServiceImpl

    /** Shared JSON codec used for the `instructions_json` column. */
    protected val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Requesting user id used by every test. */
    protected val userId = 7L

    /** Chat-capable settings profile referenced by the valid preset. */
    protected val chatSettings = TestDefaults.modelSettings1.copy(id = 1L, modelId = 1L)

    /**
     * The preset `validRequest()` attaches: model 1 plus the chat-capable settings profile 1. Its
     * references agree, which is the state every write path validates.
     */
    protected val validPreset = TestDefaults.modelPreset1.copy(
        id = 1L,
        modelId = 1L,
        modelSettingsId = chatSettings.id
    )

    /** Non-chat settings profile used to reject non-chat-capable presets. */
    protected val completionSettings = CompletionModelSettings(
        id = 5L,
        modelId = 1L,
        name = "Completion",
        suffix = null,
        temperature = null,
        maxTokens = null,
        topP = null,
        stopSequences = null,
        customParams = null
    )

    /** Creates fresh mocks, wires the service and installs the default stubs before each test. */
    @BeforeEach
    fun setUp() {
        agentRoleDao = mockk()
        agentRoleToolDao = mockk()
        agentRoleOwnershipDao = mockk()
        agentRoleSpawnableRoleDao = mockk()
        agentRoleDisabledDao = mockk()
        projectDao = mockk()
        sessionDao = mockk()
        modelPresetDao = mockk()
        settingsDao = mockk()
        toolDefinitionDao = mockk()
        transactionScope = mockk()

        service = AgentRoleServiceImpl(
            agentRoleDao = agentRoleDao,
            agentRoleToolDao = agentRoleToolDao,
            agentRoleSpawnableRoleDao = agentRoleSpawnableRoleDao,
            agentRoleOwnershipDao = agentRoleOwnershipDao,
            agentRoleDisabledDao = agentRoleDisabledDao,
            modelPresetDao = modelPresetDao,
            settingsDao = settingsDao,
            toolDefinitionDao = toolDefinitionDao,
            json = json,
            transactionScope = transactionScope,
            projectDao = projectDao,
            sessionDao = sessionDao
        )

        // The spawn allow-list DAO is always consulted (non-nullable dependency); default its reads
        // to empty and its writes to no-ops so tests focus on the behavior under test.
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(any()) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(any()) } returns emptyMap()
        coEvery { agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(any(), any()) } returns Unit

        // The disabled-markers DAO is likewise always consulted; default its reads to "enabled" for
        // both the batch and the single-role shapes, and its writes to no-ops unless a test asserts
        // specific behavior.
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(any(), any()) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(any(), any()) } returns false
        coEvery { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) } returns Unit

        // The preset DAO is consulted by every read path (single-role resolution and the list batch)
        // and by the role-attach validation. Default it to returning the valid preset so tests that do
        // not care about the configuration stay one-liners; specific tests override the stubs.
        coEvery { modelPresetDao.getPresetsByIdsForUser(any(), any()) } returns listOf(validPreset)
        coEvery { modelPresetDao.getPresetById(any()) } returns validPreset.right()
        // The preset's settings reference is loaded (and must be chat-capable) whenever the preset
        // carries one; default it to the matching chat profile.
        coEvery { settingsDao.getSettingsById(any()) } returns chatSettings.right()

        // Project-membership DAO: the role side of the membership rides the role entity (single
        // `project_id` column), so only the ownership/existence reads are defaulted here.
        coEvery { projectDao.getProjectsByIdsForUser(any(), any()) } returns emptyList()
        // The role-update sweep reads and writes; default the read to "no sessions use the role" and the
        // write to a no-op so existing update tests stay focused.
        coEvery { sessionDao.getSessionProjectPairsForRole(any()) } returns emptyList()
        coEvery { sessionDao.clearAgentRoleForSessions(any()) } returns Unit

        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    /** Clears all mocks after each test so no interaction leaks between tests. */
    @AfterEach
    fun tearDown() {
        clearMocks(
            agentRoleDao,
            agentRoleToolDao,
            agentRoleOwnershipDao,
            agentRoleSpawnableRoleDao,
            agentRoleDisabledDao,
            projectDao,
            sessionDao,
            modelPresetDao,
            settingsDao,
            toolDefinitionDao,
            transactionScope
        )
    }

    /** Builds a valid create request attaching the valid preset and a single role instruction.
     *
     * @return A valid create-role request for the happy path.
     */
    protected fun validRequest() = CreateAgentRoleRequest(
        name = "Senior Architect",
        displayName = "Architect",
        description = "Designs systems",
        modelPresetId = validPreset.id,
        toolIds = emptySet(),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
        )
    )
}
