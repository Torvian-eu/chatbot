package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.AgentRoleOwnershipDao
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleToolDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.tables.AgentRoleOwnersTable
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cascade and atomicity tests for [ProjectService.deleteProject] against a real in-memory SQLite
 * database and the real service wiring.
 *
 * The success scenarios pin the destructive cascade end-to-end: the project's member roles and their
 * dependent rows disappear, an instruction row whose last link was held by those roles is removed
 * with its owner, rows still linked by a surviving role (in another project or unassociated) and
 * never-linked rows survive, and a session selecting the project survives with its role cleared and
 * its message history intact. The rollback scenarios pin the transaction boundary: a failure raised
 * after the cascade unwinds every deletion, including the role removals and the instruction sweep,
 * and a member role that cannot be deleted aborts before the project row is removed.
 */
class ProjectServiceDeletionCascadeAtomicityTest {

    private lateinit var container: DIContainer
    private lateinit var projectService: ProjectService
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var agentRoleOwnershipDao: AgentRoleOwnershipDao
    private lateinit var agentRoleToolDao: AgentRoleToolDao
    private lateinit var agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao
    private lateinit var agentRoleDisabledDao: AgentRoleDisabledDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var testDataManager: TestDataManager

    /** Requesting user id; the seeded test user. */
    private val userId = TestDefaults.user1.id

    private val project = TestDefaults.project1
    private val otherProject = TestDefaults.project2.copy(id = 2L)

    // Project member roles (deleted), the other project's role and an unassociated role (survivors).
    private val memberRoleA = TestDefaults.agentRole1.copy(id = 10L, name = "Architect", projectId = project.id)
    private val memberRoleB = TestDefaults.agentRole2.copy(id = 12L, name = "Reviewer", projectId = project.id)
    private val otherProjectRole = TestDefaults.agentRole1.copy(id = 11L, name = "Other", projectId = otherProject.id)
    private val unassociatedRole = TestDefaults.agentRole2.copy(id = 13L, name = "Freelance", projectId = null)

    // Seeded instruction ids, filled in setUp.
    private var exclusiveId: Long = 0L
    private var sharedWithOtherProjectId: Long = 0L
    private var sharedWithUnassociatedId: Long = 0L
    private var sharedByTwoMemberRolesId: Long = 0L
    private var neverLinkedId: Long = 0L

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        projectService = container.get()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()
        agentRoleDao = container.get()
        agentRoleOwnershipDao = container.get()
        agentRoleToolDao = container.get()
        agentRoleSpawnableRoleDao = container.get()
        agentRoleDisabledDao = container.get()
        transactionScope = container.get()
        testDataManager = container.get()

        testDataManager.setup(TestDataSet(users = listOf(TestDefaults.user1)))
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.CHAT_GROUPS,
                Table.PROJECTS,
                Table.PROJECT_OWNERS,
                Table.TOOL_DEFINITIONS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.AGENT_ROLE_TOOLS,
                Table.AGENT_ROLE_SPAWNABLE_ROLES,
                Table.AGENT_ROLE_DISABLED,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES
            )
        )

        testDataManager.insertProject(project)
        testDataManager.insertProject(otherProject)
        testDataManager.insertProjectOwnership(project.id, userId)
        testDataManager.insertProjectOwnership(otherProject.id, userId)

        listOf(memberRoleA, memberRoleB, otherProjectRole, unassociatedRole).forEach { role ->
            testDataManager.insertAgentRole(role)
            testDataManager.insertAgentRoleOwnership(role.id, userId)
        }

        // Dependent rows the member roles must take with them.
        val toolId = container.get<ToolDefinitionDao>().insertToolDefinition(
            name = "web_search",
            description = "Search the web",
            type = ToolType.BUILTIN_WORKER,
            config = buildJsonObject {},
            inputSchema = buildJsonObject {},
            outputSchema = null,
            isEnabled = true
        ).id
        testDataManager.insertAgentRoleTool(memberRoleA.id, toolId)
        testDataManager.insertAgentRoleDisabled(memberRoleA.id, userId)
        // Role A spawns role B, and the surviving unassociated role holds a grant TO the deleted role A;
        // both grant directions referencing a deleted role must cascade away.
        agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(memberRoleA.id, setOf(memberRoleB.id))
        agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(unassociatedRole.id, setOf(memberRoleA.id))

        // Instruction fates: exclusive and two-member-shared are doomed; the two shared-with-survivor
        // rows and the never-linked row survive.
        exclusiveId = seedInstruction("Exclusive")
        sharedWithOtherProjectId = seedInstruction("SharedOtherProject")
        sharedWithUnassociatedId = seedInstruction("SharedUnassociated")
        sharedByTwoMemberRolesId = seedInstruction("SharedTwoMembers")
        neverLinkedId = seedInstruction("NeverLinked")

        agentRoleInstructionDao.replaceInstructionsForRole(
            memberRoleA.id,
            listOf(exclusiveId, sharedWithOtherProjectId, sharedByTwoMemberRolesId)
        )
        agentRoleInstructionDao.replaceInstructionsForRole(memberRoleB.id, listOf(sharedWithUnassociatedId, sharedByTwoMemberRolesId))
        agentRoleInstructionDao.replaceInstructionsForRole(otherProjectRole.id, listOf(sharedWithOtherProjectId))
        agentRoleInstructionDao.replaceInstructionsForRole(unassociatedRole.id, listOf(sharedWithUnassociatedId))

        // A session selecting the project with role A attached, and one of its messages: the session
        // must survive with only its role cleared and the message must stay intact.
        testDataManager.insertChatSession(
            TestDefaults.chatSession1.copy(
                id = 50L,
                groupId = null,
                agentRoleId = memberRoleA.id,
                projectId = project.id
            )
        )
        testDataManager.insertChatMessage(TestDefaults.chatMessage1.copy(id = 500L, sessionId = 50L))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Seeds an owned instruction row.
     *
     * @param name The row's display label.
     * @return The new row's id.
     */
    private suspend fun seedInstruction(name: String): Long {
        val id = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = name, message = "Text", custom = null)
            .id
        instructionOwnershipDao.setOwner(id, userId)
        return id
    }

    /**
     * Asserts a row and its ownership link are both gone.
     *
     * @param instructionId The row id to check.
     */
    private suspend fun assertRowAndOwnerGone(instructionId: Long) {
        assertTrue(instructionDao.getInstructionById(instructionId).isLeft(), "row $instructionId must be gone")
        assertTrue(instructionOwnershipDao.getOwner(instructionId).isLeft(), "owner of $instructionId must be gone")
    }

    /**
     * Asserts a row and its ownership link both survive.
     *
     * @param instructionId The row id to check.
     */
    private suspend fun assertRowAndOwnerKept(instructionId: Long) {
        assertTrue(instructionDao.getInstructionById(instructionId).isRight(), "row $instructionId must survive")
        assertTrue(instructionOwnershipDao.getOwner(instructionId).isRight(), "owner of $instructionId must survive")
    }

    /**
     * Runs the cascade and asserts the project-level summary plus the member roles' disappearance and
     * the dependent-row cascades.
     */
    @Test
    fun `deleting a project cascades its roles and the last-link instructions while survivors stay`() = runTest {
        val survivorLinksBefore = agentRoleInstructionDao.getLinksForRoles(
            listOf(otherProjectRole.id, unassociatedRole.id)
        )

        val result = projectService.deleteProject(userId, project.id)

        val summary = result.getOrNull() ?: error("delete must succeed: ${result.leftOrNull()}")
        assertEquals(listOf(memberRoleA.id, memberRoleB.id), summary.deletedAgentRoleIds)
        assertEquals(
            setOf(exclusiveId, sharedByTwoMemberRolesId),
            summary.deletedInstructionIds.toSet(),
            "only rows whose last link was held by a member role are removed"
        )
        assertEquals(
            setOf(sharedWithOtherProjectId, sharedWithUnassociatedId),
            summary.retainedInstructionIds.toSet(),
            "rows still linked by a surviving role are reported retained"
        )

        // The member roles and their dependent rows must be gone.
        assertTrue(agentRoleDao.getRoleById(memberRoleA.id).isLeft(), "role A must be gone")
        assertTrue(agentRoleDao.getRoleById(memberRoleB.id).isLeft(), "role B must be gone")
        assertTrue(agentRoleOwnershipDao.getOwner(memberRoleA.id).isLeft(), "role A ownership must be gone")
        assertTrue(agentRoleToolDao.getToolsForRoles(listOf(memberRoleA.id))[memberRoleA.id].isNullOrEmpty())
        assertFalse(agentRoleDisabledDao.isRoleDisabled(userId, memberRoleA.id), "role A disabled marker must be gone")
        assertTrue(agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(unassociatedRole.id).isEmpty(), "grant to role A must be gone")

        // Other projects' roles, the unassociated role and the other project row must be untouched.
        assertTrue(agentRoleDao.getRoleById(otherProjectRole.id).isRight(), "other project's role must survive")
        assertTrue(agentRoleDao.getRoleById(unassociatedRole.id).isRight(), "unassociated role must survive")
        assertEquals(null, testDataManager.getProject(project.id), "the project row must be gone")
        assertTrue(testDataManager.getProject(otherProject.id) != null, "the other project must survive")

        // Instruction fates: rows whose last link was cut are removed, rows a surviving role still
        // links and never-linked rows stay.
        assertRowAndOwnerGone(exclusiveId)
        assertRowAndOwnerGone(sharedByTwoMemberRolesId)
        assertRowAndOwnerKept(sharedWithOtherProjectId)
        assertRowAndOwnerKept(sharedWithUnassociatedId)
        assertRowAndOwnerKept(neverLinkedId)

        // A surviving role keeps every link row, in the same order as before the cascade.
        assertEquals(
            survivorLinksBefore,
            agentRoleInstructionDao.getLinksForRoles(listOf(otherProjectRole.id, unassociatedRole.id)),
            "surviving roles must keep their instruction links"
        )

        // The session survives with its role cleared; the FK also nulls its project.
        val session = testDataManager.getChatSession(50L) ?: error("session must survive")
        assertEquals(null, session.agentRoleId, "the session's role must be cleared")

        // The session's message is untouched by the deletion (the session row survives it).
        val message = testDataManager.getChatMessage(500L) ?: error("the session's message must survive")
        assertEquals(50L, message.sessionId, "the message must stay attached to its session")
        assertEquals(TestDefaults.chatMessage1.content, message.content, "the message content must be untouched")
    }

    /**
     * Verifies the whole cascade rolls back when a failure is raised after it: nothing is persisted,
     * not even the role deletions or their instruction sweeps.
     */
    @Test
    fun `a failure after the cascade rolls everything back`() = runTest {
        // The nested deleteProject call joins this outermost transaction block; failing the block after
        // the cascade must unwind the project delete, the role deletions and the instruction sweep.
        val outcome = transactionScope.transaction {
            either {
                projectService.deleteProject(userId, project.id)
                    .mapLeft { error -> "delete must succeed before the late failure: $error" }
                    .bind()
                ensure(false) { "late failure" }
            }
        }

        assertTrue(outcome.isLeft(), "the late failure must fail the transaction")
        assertTrue(testDataManager.getProject(project.id) != null, "the project must survive the rollback")
        assertTrue(agentRoleDao.getRoleById(memberRoleA.id).isRight(), "role A must survive the rollback")
        assertTrue(agentRoleDao.getRoleById(memberRoleB.id).isRight(), "role B must survive the rollback")
        assertRowAndOwnerKept(exclusiveId)
        assertRowAndOwnerKept(sharedByTwoMemberRolesId)
        val session = testDataManager.getChatSession(50L) ?: error("session must survive")
        assertEquals(memberRoleA.id, session.agentRoleId, "the session's role must be restored by the rollback")
    }

    /**
     * Verifies the fail-fast path unwinds the whole cascade when a later member role cannot be
     * deleted: the earlier role is already gone by then, so its restoration is the proof that the
     * thrown exception rolls the transaction back and leaves no partial state.
     */
    @Test
    fun `a member role that cannot be deleted aborts the cascade and restores the earlier role`() = runTest {
        // Member roles are deleted in ascending id order, so role A (10) is removed before role B
        // (12). Removing B's ownership row makes the delivered deleteRole report NotFound for it.
        val linksBefore = agentRoleInstructionDao.getLinksForRoles(
            listOf(memberRoleA.id, memberRoleB.id, otherProjectRole.id, unassociatedRole.id)
        )
        transactionScope.transaction {
            AgentRoleOwnersTable.deleteWhere { AgentRoleOwnersTable.roleId eq memberRoleB.id }
        }

        assertFailsWith<IllegalStateException> { projectService.deleteProject(userId, project.id) }

        // The aborted cascade leaves everything as before: the project row, both roles and every
        // instruction row, owner and link row.
        assertTrue(testDataManager.getProject(project.id) != null, "the project must survive")
        assertTrue(agentRoleDao.getRoleById(memberRoleA.id).isRight(), "the earlier role must be restored")
        assertTrue(agentRoleDao.getRoleById(memberRoleB.id).isRight(), "the later role must survive")
        assertRowAndOwnerKept(exclusiveId)
        assertRowAndOwnerKept(sharedByTwoMemberRolesId)
        assertRowAndOwnerKept(sharedWithOtherProjectId)
        assertRowAndOwnerKept(sharedWithUnassociatedId)
        assertRowAndOwnerKept(neverLinkedId)
        assertEquals(
            linksBefore,
            agentRoleInstructionDao.getLinksForRoles(
                listOf(memberRoleA.id, memberRoleB.id, otherProjectRole.id, unassociatedRole.id)
            ),
            "every link row must be restored"
        )

        val session = testDataManager.getChatSession(50L) ?: error("session must survive")
        assertEquals(memberRoleA.id, session.agentRoleId, "the session's role must be untouched")
    }
}
