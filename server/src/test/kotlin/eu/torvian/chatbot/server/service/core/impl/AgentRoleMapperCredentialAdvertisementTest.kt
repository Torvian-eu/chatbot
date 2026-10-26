package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.agent.CredentialProfilesAdvertisement
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Tests for [AgentRoleMapper.loadCredentialProfilesAdvertisement]: a profile is advertised only on the
 * intersection of the role's `terminal_session` workers, the role link and the enabled flag.
 */
class AgentRoleMapperCredentialAdvertisementTest {

    /** Mocked role-row DAO; unused by this loader but required by the mapper's constructor. */
    private val agentRoleDao = mockk<AgentRoleDao>()

    /** Mocked tool DAO backing the `terminal_session` worker resolution. */
    private val toolDefinitionDao = mockk<ToolDefinitionDao>()

    /** Mocked project DAO; unused by this loader but required by the mapper's constructor. */
    private val projectDao = mockk<ProjectDao>()

    /** Mocked profile DAO backing the per-worker enabled-profile read. */
    private val credentialProfileDao = mockk<WorkerCredentialProfileDao>()

    /** Mocked link DAO backing the role-scoped profile id set. */
    private val credentialProfileLinkDao = mockk<WorkerCredentialProfileAgentRoleDao>()

    /** Mocked worker DAO backing the group labels. */
    private val workerDao = mockk<WorkerDao>()

    /** Mapper under test, wired to the mocked DAOs. */
    private val mapper = AgentRoleMapper(
        agentRoleDao = agentRoleDao,
        toolDefinitionDao = toolDefinitionDao,
        projectDao = projectDao,
        credentialProfileDao = credentialProfileDao,
        credentialProfileLinkDao = credentialProfileLinkDao,
        workerDao = workerDao,
        json = Json { ignoreUnknownKeys = true }
    )

    /** Id of the role being mapped. */
    private val agentRoleId = 42L

    /** Worker the terminal-session tool definition targets. */
    private val workerId = 5L

    /**
     * Verifies that only the profiles linked to the mapped role reach the advertisement.
     */
    @Test
    fun `advertises only profiles linked to the mapped role`() = runTest {
        givenTerminalSessionOn(listOf(workerId))
        coEvery { credentialProfileLinkDao.getProfileIdsByAgentRoleId(agentRoleId) } returns setOf(1L, 3L)
        coEvery { workerDao.getWorkerById(workerId) } returns worker(workerId, "workstation").right()
        coEvery { credentialProfileDao.getEnabledProfilesByWorkerId(workerId) } returns listOf(
            profile(id = 1L, name = "banking"),
            profile(id = 2L, name = "linked-to-another-role"),
            profile(id = 3L, name = "release"),
        )

        val advertisement = mapper.loadCredentialProfilesAdvertisement(
            roleToolIds = TERMINAL_SESSION_TOOL_ID_SET,
            agentRoleId = agentRoleId,
        )

        assertEquals(listOf("banking", "release"), advertisement.singleGroupProfileNames())
    }

    /**
     * Verifies that a role with no link at all produces no groups, which collapses the section.
     */
    @Test
    fun `returns no groups when the role links no profile`() = runTest {
        givenTerminalSessionOn(listOf(workerId))
        coEvery { credentialProfileLinkDao.getProfileIdsByAgentRoleId(agentRoleId) } returns emptySet()

        val advertisement = mapper.loadCredentialProfilesAdvertisement(
            roleToolIds = TERMINAL_SESSION_TOOL_ID_SET,
            agentRoleId = agentRoleId,
        )

        assertEquals(CredentialProfilesAdvertisement(groups = emptyList()), advertisement)
    }

    /**
     * Verifies that a role without a `terminal_session` tool definition sees no work at all: neither a
     * link nor a profile read happens.
     */
    @Test
    fun `returns no groups when the role targets no terminal session worker`() = runTest {
        givenTerminalSessionOn(emptyList())

        val advertisement = mapper.loadCredentialProfilesAdvertisement(
            roleToolIds = TERMINAL_SESSION_TOOL_ID_SET,
            agentRoleId = agentRoleId,
        )

        assertEquals(CredentialProfilesAdvertisement(groups = emptyList()), advertisement)
    }

    /**
     * Verifies worker order and per-worker creation order survive the role filter, and that a profile
     * linked to the role on a later worker is not listed under an earlier one.
     */
    @Test
    fun `keeps worker order and creation order while filtering by role link`() = runTest {
        givenTerminalSessionOn(listOf(workerId, otherWorkerId))
        coEvery { credentialProfileLinkDao.getProfileIdsByAgentRoleId(agentRoleId) } returns setOf(1L, 2L)
        coEvery { workerDao.getWorkerById(workerId) } returns worker(workerId, "first").right()
        coEvery { workerDao.getWorkerById(otherWorkerId) } returns worker(otherWorkerId, "second").right()
        coEvery { credentialProfileDao.getEnabledProfilesByWorkerId(workerId) } returns listOf(
            profile(id = 2L, name = "second-created", workerId = workerId),
            profile(id = 1L, name = "first-created", workerId = workerId),
        )
        coEvery { credentialProfileDao.getEnabledProfilesByWorkerId(otherWorkerId) } returns listOf(
            profile(id = 3L, name = "other-worker", workerId = otherWorkerId),
        )

        val advertisement = mapper.loadCredentialProfilesAdvertisement(
            roleToolIds = TERMINAL_SESSION_TOOL_ID_SET,
            agentRoleId = agentRoleId,
        )

        assertEquals(listOf(workerId, otherWorkerId), advertisement.groups.map { it.workerId })
        assertEquals(listOf("first", "second"), advertisement.groups.map { it.workerName })
        // The DAO order is preserved (creation order) and the unlinked profile of the second worker is
        // dropped, so its group stays but carries no profiles.
        assertEquals(
            listOf(listOf("second-created", "first-created"), emptyList()),
            advertisement.groups.map { group -> group.profiles.map { it.name } }
        )
    }

    /**
     * @param names Profile names of the only group of the advertisement.
     * @return The names in the order they would be rendered.
     */
    private fun CredentialProfilesAdvertisement.singleGroupProfileNames(): List<String> {
        assertEquals(1, groups.size)
        return groups.single().profiles.map { it.name }
    }

    /**
     * @param workerIds Workers whose `terminal_session` tool definition is attached to the role.
     */
    private fun givenTerminalSessionOn(workerIds: List<Long>) {
        coEvery { toolDefinitionDao.getToolDefinitionsByIds(TERMINAL_SESSION_TOOL_ID_SET) } returns workerIds.map {
            BuiltInWorkerToolDefinition(
                id = TERMINAL_SESSION_TOOL_ID,
                name = "terminal_session",
                description = "",
                config = JsonObject(emptyMap()),
                inputSchema = JsonObject(emptyMap()),
                isEnabled = true,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                workerId = it,
                builtInToolName = "terminal_session",
            )
        }
    }

    /**
     * @param id Profile identifier.
     * @param name Profile name.
     * @param workerId Worker the profile is bound to.
     * @return A stored profile row with the defaults this test does not vary.
     */
    private fun profile(id: Long, name: String, workerId: Long = this.workerId): WorkerCredentialProfileEntity =
        WorkerCredentialProfileEntity(
            id = id,
            workerId = workerId,
            name = name,
            description = null,
            secretRef = "secret-$id",
            command = "/usr/bin/env",
            args = emptyList(),
            channel = CredentialChannel.ENV,
            envName = "TOKEN",
            fileName = null,
            askpassUsername = null,
            environmentVariables = emptyMap(),
            askpassVariables = emptyList(),
            isEnabled = true,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    /**
     * @param id Worker identifier.
     * @param displayName Worker display name.
     * @return A stored worker row carrying the identifier and name only.
     */
    private fun worker(id: Long, displayName: String): WorkerEntity = WorkerEntity(
        id = id,
        workerUid = "uid-$id",
        ownerUserId = 1L,
        displayName = displayName,
        certificatePem = "pem",
        certificateFingerprint = "fp",
        allowedScopes = emptyList(),
        createdAt = Instant.fromEpochMilliseconds(0),
        lastSeenAt = null,
        toolNamePrefix = null,
    )

    private companion object {
        /** Id of the `terminal_session` tool definition attached to the role. */
        const val TERMINAL_SESSION_TOOL_ID = 900L

        /** Tool id set the loader reads the role's definitions from. */
        val TERMINAL_SESSION_TOOL_ID_SET = setOf(TERMINAL_SESSION_TOOL_ID)

        /** Second worker of the multi-worker case. */
        const val otherWorkerId = 6L
    }
}
