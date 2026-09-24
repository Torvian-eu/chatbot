package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.CreateUserError
import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.security.PasswordService
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Rollback coverage for the shared account provisioning pipeline of [UserServiceImpl].
 *
 * Runs creation against a real database and transaction boundary with tool seeding failing after
 * the users row insert, asserting that a failed provisioning leaves no account behind.
 */
class UserServiceImplProvisioningRollbackTest {
    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var userDao: UserDao
    private lateinit var passwordService: PasswordService
    private lateinit var roleDao: RoleDao
    private lateinit var userRoleAssignmentDao: UserRoleAssignmentDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var operatorToolDefinitionSeeder: OperatorToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionSeeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var userService: UserService

    private val allUsersGroup = UserGroup(
        id = 1L,
        name = CommonUserGroups.ALL_USERS,
        description = "All users group"
    )

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        // Spy on the real DAO so the test can observe the insert that precedes the injected failure
        userDao = spyk(container.get<UserDao>())
        passwordService = mockk()
        roleDao = mockk()
        userRoleAssignmentDao = mockk()
        userGroupService = mockk()
        operatorToolDefinitionSeeder = mockk()
        serverBuiltInToolDefinitionSeeder = mockk()

        // Only the users table is real; group membership and tool seeding stay test doubles so the
        // post-insert failure can be injected deterministically.
        testDataManager.createTables(setOf(Table.USERS))

        // Happy-path stubs; the test overrides the step whose failure it exercises
        every { passwordService.validatePasswordStrength(any()) } returns Unit.right()
        every { passwordService.hashPassword(any()) } returns "hashed"
        coEvery { userGroupService.getAllUsersGroup() } returns allUsersGroup.right()
        coEvery { userGroupService.addUserToGroup(any(), any()) } returns Unit.right()
        coEvery { operatorToolDefinitionSeeder.ensureForUser(any()) } returns emptyList<OperatorToolDefinition>().right()
        coEvery { serverBuiltInToolDefinitionSeeder.ensureForUser(any()) } returns emptyList<ServerBuiltInToolDefinition>().right()

        userService = UserServiceImpl(
            userDao = userDao,
            passwordService = passwordService,
            roleDao = roleDao,
            userRoleAssignmentDao = userRoleAssignmentDao,
            userGroupService = userGroupService,
            // The real transaction boundary is the subject under test: it must roll back on failure
            transactionScope = container.get(),
            policy = AccountValidationPolicy(
                passwordConfig = PasswordValidationConfig(),
                usernameConfig = UsernameValidationConfig()
            ),
            operatorToolDefinitionSeeder = operatorToolDefinitionSeeder,
            serverBuiltInToolDefinitionSeeder = serverBuiltInToolDefinitionSeeder
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `createUser rolls back the users row when tool seeding fails after the insert`() = runTest {
        // Given: operator-tool seeding fails after the users row insert
        coEvery { operatorToolDefinitionSeeder.ensureForUser(any()) } returns
                SeedOperatorToolsError.LinkageFailed(OperatorToolDefinitionError.NotFound(1L)).left()

        // When
        val result = userService.createUser("rollbackuser", "ValidPass123!", null, requiresPasswordChange = true)

        // Then: the failure surfaces as the mapped provisioning error
        val error = assertIs<CreateUserError.ProvisioningFailed>(result.leftOrNull())
        assertEquals("Failed to initialize user tool configuration", error.reason)

        // The insert ran before the failure...
        coVerify(exactly = 1) { userDao.insertUser("rollbackuser", "hashed", null, UserStatus.ACTIVE, true) }

        // ...and the transaction boundary rolled it back, so no USERS row survives the failure.
        assertTrue(
            userDao.getUserByUsername("rollbackuser").isLeft(),
            "the users row insert must be rolled back after a post-insert provisioning failure"
        )
    }
}
