package eu.torvian.chatbot.app.viewmodel.admin

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.RoleRepository
import eu.torvian.chatbot.app.repository.UserRepository
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.service.auth.DefaultAuthValidationService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock

/**
 * Unit tests for the admin create-user flow of [UserManagementViewModel].
 *
 * Covers client-side validation gating before submission, per-field error reporting for
 * duplicate-account conflicts, rule reuse from the shared account policy, and preservation of the
 * optional email and require-password-change behaviors. Also covers the validation-config
 * exposure used to render requirement hints. Submissions run in the ViewModel's own scope on the
 * shared test scheduler; tests settle that work with `advanceUntilIdle()` before asserting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserManagementViewModelCreateUserTest {

    private lateinit var userRepository: UserRepository
    private lateinit var roleRepository: RoleRepository
    private lateinit var notificationService: NotificationService
    private lateinit var authValidationService: AuthValidationService

    /**
     * Dispatcher shared by the ViewModel's scope and the test body so a single scheduler drives
     * both and settling calls can drain the ViewModel's pending work.
     */
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: UserManagementViewModel

    @BeforeTest
    fun setup() {
        userRepository = mockk(relaxed = true)
        roleRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        authValidationService = mockk(relaxed = true)
        testDispatcher = UnconfinedTestDispatcher()
        testScope = CoroutineScope(testDispatcher)

        every { userRepository.users } returns
            MutableStateFlow<DataState<RepositoryError, List<UserWithDetails>>>(DataState.Idle)

        // Validation passes by default so individual tests can stub specific field failures.
        every { authValidationService.validateUsername(any()) } returns null
        every { authValidationService.validateEmail(any()) } returns null
        every { authValidationService.validatePassword(any()) } returns null
        every { authValidationService.validateConfirmPassword(any(), any()) } returns null

        viewModel = createViewModel(authValidationService)
    }

    @AfterTest
    fun tearDown() {
        clearMocks(userRepository, roleRepository, notificationService, authValidationService)
    }

    // ===== Successful Creation Tests =====

    @Test
    fun `submitCreateUser provisions the account and closes the dialog when all fields are valid`() = runTest(testDispatcher) {
        // Arrange
        coEvery {
            userRepository.createUser("newuser", "Password123!", "new@example.com", true)
        } returns createdUser().right()

        viewModel.startCreatingUser()
        fillCreateForm()

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        coVerify { userRepository.createUser("newuser", "Password123!", "new@example.com", true) }
        assertEquals(UserManagementDialogState.None, viewModel.state.value.dialogState)
    }

    @Test
    fun `submitCreateUser omits the email when none is provided`() = runTest(testDispatcher) {
        // Arrange
        coEvery {
            userRepository.createUser("newuser", "Password123!", null, true)
        } returns createdUser().right()

        viewModel.startCreatingUser()
        fillCreateForm(email = "   ")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        coVerify { userRepository.createUser("newuser", "Password123!", null, true) }
    }

    @Test
    fun `submitCreateUser forwards a disabled password-change requirement`() = runTest(testDispatcher) {
        // Arrange
        coEvery {
            userRepository.createUser("newuser", "Password123!", "new@example.com", false)
        } returns createdUser().right()

        viewModel.startCreatingUser()
        viewModel.updateCreateUserForm(requiresPasswordChange = false)
        fillCreateForm()

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        coVerify { userRepository.createUser("newuser", "Password123!", "new@example.com", false) }
    }

    @Test
    fun `startCreatingUser requires a password change on first login by default`() {
        // Act
        viewModel.startCreatingUser()

        // Assert
        assertTrue(currentCreateForm().requiresPasswordChange)
    }

    // ===== Client-Side Validation Tests =====

    @Test
    fun `submitCreateUser reports the username rule and blocks the request`() = runTest(testDispatcher) {
        // Arrange
        every {
            authValidationService.validateUsername("bad name")
        } returns "Username can only contain letters, numbers, hyphens, and underscores"

        viewModel.startCreatingUser()
        fillCreateForm(username = "bad name")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        assertEquals(
            "Username can only contain letters, numbers, hyphens, and underscores",
            currentCreateForm().usernameError
        )
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `submitCreateUser reports the email rule and blocks the request`() = runTest(testDispatcher) {
        // Arrange
        every { authValidationService.validateEmail("not-an-email") } returns "Please enter a valid email address"

        viewModel.startCreatingUser()
        fillCreateForm(email = "not-an-email")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        assertEquals("Please enter a valid email address", currentCreateForm().emailError)
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `submitCreateUser reports the password rule and blocks the request`() = runTest(testDispatcher) {
        // Arrange
        every { authValidationService.validatePassword("weak") } returns "Password must be at least 8 characters"

        viewModel.startCreatingUser()
        fillCreateForm(password = "weak", confirmPassword = "weak")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        assertEquals("Password must be at least 8 characters", currentCreateForm().passwordError)
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `submitCreateUser reports a password confirmation mismatch and blocks the request`() = runTest(testDispatcher) {
        // Arrange
        every {
            authValidationService.validateConfirmPassword("Password123!", "Password456!")
        } returns "Passwords do not match"

        viewModel.startCreatingUser()
        fillCreateForm(password = "Password123!", confirmPassword = "Password456!")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        assertEquals("Passwords do not match", currentCreateForm().confirmPasswordError)
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `submitCreateUser validates fields with the shared account policy`() = runTest(testDispatcher) {
        // Arrange: real validation service so the registration rules apply unchanged
        viewModel = createViewModel(DefaultAuthValidationService())
        viewModel.startCreatingUser()
        fillCreateForm(username = "ab", email = "not-an-email", password = "weak", confirmPassword = "different")

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        val form = currentCreateForm()
        assertEquals("Username must be at least 3 characters", form.usernameError)
        assertEquals("Please enter a valid email address", form.emailError)
        assertEquals("Password must be at least 8 characters", form.passwordError)
        assertEquals("Passwords do not match", form.confirmPasswordError)
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `submitCreateUser reports required fields with the shared account policy`() = runTest(testDispatcher) {
        // Arrange: real validation service on an untouched form
        viewModel = createViewModel(DefaultAuthValidationService())
        viewModel.startCreatingUser()

        // Act
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert: email stays optional, everything else gets a specific message
        val form = currentCreateForm()
        assertEquals("Username is required", form.usernameError)
        assertNull(form.emailError)
        assertEquals("Password is required", form.passwordError)
        assertEquals("Please confirm your password", form.confirmPasswordError)
        coVerify(exactly = 0) { userRepository.createUser(any(), any(), any(), any()) }
    }

    // ===== Server-Side Error Mapping Tests =====

    @Test
    fun `submitCreateUser maps a duplicate username conflict onto the username field`() = runTest(testDispatcher) {
        // Arrange
        coEvery { userRepository.createUser(any(), any(), any(), any()) } returns serverError(
            statusCode = 409,
            code = "already-exists",
            message = "Username already exists",
            details = mapOf("field" to "username")
        ).left()

        viewModel.startCreatingUser()
        fillCreateForm()
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        val form = currentCreateForm()
        assertEquals("Username is already taken. Please choose a different one.", form.usernameError)
        assertNull(form.generalError)
        assertFalse(form.isLoading)
    }

    @Test
    fun `submitCreateUser maps a duplicate email conflict onto the email field`() = runTest(testDispatcher) {
        // Arrange
        coEvery { userRepository.createUser(any(), any(), any(), any()) } returns serverError(
            statusCode = 409,
            code = "already-exists",
            message = "Email already exists",
            details = mapOf("field" to "email")
        ).left()

        viewModel.startCreatingUser()
        fillCreateForm()
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        val form = currentCreateForm()
        assertEquals("Email is already registered. Please use a different one.", form.emailError)
        assertNull(form.generalError)
        assertFalse(form.isLoading)
    }

    @Test
    fun `submitCreateUser keeps server failures without field details in the general error area`() = runTest(testDispatcher) {
        // Arrange
        val error = serverError(statusCode = 500, code = "internal", message = "Internal error")
        coEvery { userRepository.createUser(any(), any(), any(), any()) } returns error.left()

        viewModel.startCreatingUser()
        fillCreateForm()
        viewModel.submitCreateUser()

        advanceUntilIdle()

        // Assert
        val form = currentCreateForm()
        assertEquals(error.message, form.generalError)
        assertNull(form.usernameError)
        assertNull(form.emailError)
        assertFalse(form.isLoading)
    }

    // ===== Form State Management Tests =====

    @Test
    fun `updateCreateUserForm clears a field error once the field is edited`() = runTest(testDispatcher) {
        // Arrange
        every { authValidationService.validateUsername("bad") } returns "Username is required"

        viewModel.startCreatingUser()
        fillCreateForm(username = "bad")
        viewModel.submitCreateUser()
        advanceUntilIdle()
        assertNotNull(currentCreateForm().usernameError)

        // Act
        viewModel.updateCreateUserForm(username = "good")

        // Assert
        assertNull(currentCreateForm().usernameError)
    }

    @Test
    fun `updateCreateUserForm clears the confirmation error when the password is edited`() = runTest(testDispatcher) {
        // Arrange
        every {
            authValidationService.validateConfirmPassword(any(), any())
        } returns "Passwords do not match"

        viewModel.startCreatingUser()
        fillCreateForm(password = "Password123!", confirmPassword = "Password456!")
        viewModel.submitCreateUser()
        advanceUntilIdle()
        assertNotNull(currentCreateForm().confirmPasswordError)

        // Act: changing either half of the pair invalidates the pending verdict
        viewModel.updateCreateUserForm(password = "Password789!")

        // Assert
        assertNull(currentCreateForm().confirmPasswordError)
    }

    // ===== Validation Policy Exposure Tests =====

    @Test
    fun `UserManagementViewModel exposes the active validation rules for requirement hints`() {
        // Arrange: configs are read when the ViewModel is constructed
        val passwordConfig = PasswordValidationConfig(minLength = 10)
        val usernameConfig = UsernameValidationConfig(minLength = 4)
        every { authValidationService.passwordValidationConfig } returns passwordConfig
        every { authValidationService.usernameValidationConfig } returns usernameConfig

        // Act
        viewModel = createViewModel(authValidationService)

        // Assert
        assertEquals(passwordConfig, viewModel.passwordValidationConfig)
        assertEquals(usernameConfig, viewModel.usernameValidationConfig)
    }

    // ===== Helpers =====

    /**
     * Builds the ViewModel under test with the given validation service and shared repository mocks.
     *
     * @param validationService The validation service the ViewModel should use
     * @return A freshly constructed [UserManagementViewModel]
     */
    private fun createViewModel(validationService: AuthValidationService): UserManagementViewModel =
        UserManagementViewModel(
            userRepository = userRepository,
            roleRepository = roleRepository,
            notificationService = notificationService,
            authValidationService = validationService,
            normalScope = testScope
        )

    /**
     * Returns the form state of the currently open create-user dialog.
     *
     * @return The live [CreateUserFormState] captured from the ViewModel state
     */
    private fun currentCreateForm(): CreateUserFormState =
        (viewModel.state.value.dialogState as UserManagementDialogState.CreateUser).formState

    /**
     * Fills the create form field by field the way the dialog does.
     *
     * @param username The username value to enter
     * @param email The email value to enter
     * @param password The password value to enter
     * @param confirmPassword The confirmation value to enter
     */
    private fun fillCreateForm(
        username: String = "newuser",
        email: String = "new@example.com",
        password: String = "Password123!",
        confirmPassword: String = password
    ) {
        viewModel.updateCreateUserForm(username = username)
        viewModel.updateCreateUserForm(email = email)
        viewModel.updateCreateUserForm(password = password)
        viewModel.updateCreateUserForm(confirmPassword = confirmPassword)
    }

    /**
     * Creates a public user payload as returned by a successful account creation.
     *
     * @return A sample [User] with active status
     */
    private fun createdUser(): User = User(
        id = 1L,
        username = "newuser",
        email = "new@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Clock.System.now(),
        lastLogin = Clock.System.now()
    )

    /**
     * Builds a repository error wrapping a server API error for submission-failure scenarios.
     *
     * @param statusCode The HTTP status code of the server response
     * @param code The machine-readable API error code
     * @param message The human-readable server message
     * @param details Optional structured details such as the conflicting field
     * @return The repository error the user repository would return
     */
    private fun serverError(
        statusCode: Int,
        code: String,
        message: String,
        details: Map<String, String>? = null
    ): RepositoryError = RepositoryError.DataFetchError(
        apiResourceError = ApiResourceError.ServerError(
            apiError = ApiError(
                statusCode = statusCode,
                code = code,
                message = message,
                details = details
            )
        ),
        contextMessage = "Failed to create user"
    )
}
